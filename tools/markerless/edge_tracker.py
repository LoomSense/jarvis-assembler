"""Method A: edge-based model tracking with a table-plane prior.

Pose model: the object rests on a known table plane (T_world_table, z up), so
    T_world_obj(x, y, yaw) = T_world_table @ Trans(x, y, 0) @ Rz(yaw)
and the 6-DoF refinement adds (z, roll, pitch) with a soft prior towards 0.

Image side: oriented chamfer. Canny edges are split into NB orientation bins;
per bin a truncated distance transform (and its gradient) is precomputed, at
two pyramid levels. A model edge sample only "sees" image edges whose gradient
orientation matches its projected normal (+-1 bin), which kills most of the
wood-grain / clutter edges.

Solver: Levenberg-Marquardt on robust (Huber, truncated) DT residuals; the
Jacobian is grad(DT) x d(u,v)/d(theta) (finite differences on the projection).
Visibility: back-face test + numba z-buffer (model.visible_samples).
Global init: exhaustive (x, y, yaw) grid on the table inside the view frustum,
scored with the coarse oriented chamfer, top-K refined with LM.
"""
import time

import cv2
import numpy as np

from model import visible_samples, zbuffer

NB = 8
EDGE_PCT = 85.0
EDGE_FLOOR = 24.0
OK_INL, OK_COST = 0.35, 0.4
REGION_W = 0.7
INIT_FRAMES = 3   # frames of multi-hypothesis init before committing
INIT_KEEP = 6
HOLD_FRAMES = 8   # bad frames tolerated (hold last pose) before re-init
MAX_RANGE = 1.2
COARSE_TAU_M = 0.03


def rot_x(a):
    c, s = np.cos(a), np.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):
    c, s = np.cos(a), np.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_z(a):
    c, s = np.cos(a), np.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def pose_from_params(T_wt, th):
    """th = [x, y, yaw] or [x, y, yaw, z, roll, pitch] in the table frame."""
    T = np.eye(4)
    x, y, yaw = th[:3]
    z, roll, pitch = (th[3], th[4], th[5]) if len(th) == 6 else (0.0, 0.0, 0.0)
    T[:3, :3] = rot_z(yaw) @ rot_y(pitch) @ rot_x(roll)
    T[:3, 3] = [x, y, z]
    return T_wt @ T


class Level:
    def __init__(self, gray, K, scale, tau, tau_coarse=None):
        h, w = gray.shape
        self.w, self.h, self.tau = w, h, tau
        self.K = K.copy()
        g = cv2.GaussianBlur(gray, (5, 5), 1.0)
        dx = cv2.Sobel(g, cv2.CV_16S, 1, 0, ksize=3)
        dy = cv2.Sobel(g, cv2.CV_16S, 0, 1, ksize=3)
        mag = np.hypot(dx.astype(np.float32), dy.astype(np.float32))
        hi = max(EDGE_FLOOR, float(np.percentile(mag, EDGE_PCT)))
        E = cv2.Canny(dx, dy, hi * 0.4, hi, L2gradient=True) > 0
        self.edges = E
        ang = (np.degrees(np.arctan2(dy.astype(np.float32), dx.astype(np.float32))) % 180.0)
        b = np.floor(ang / (180.0 / NB)).astype(np.int32) % NB
        self.DT = np.empty((NB, h, w), np.float32)
        self.GX = np.empty_like(self.DT)
        self.GY = np.empty_like(self.DT)
        if tau_coarse:
            self.DTc = np.empty_like(self.DT)
        self.coarse = None
        for k in range(NB):
            m = E & ((b == k) | (b == (k + 1) % NB) | (b == (k - 1) % NB))
            dfull = cv2.distanceTransform((~m).astype(np.uint8), cv2.DIST_L2, 3)
            d = np.minimum(dfull, tau)
            self.DT[k] = d
            if tau_coarse:
                self.DTc[k] = np.minimum(dfull, tau_coarse)
            self.GX[k] = cv2.Sobel(d, cv2.CV_32F, 1, 0, ksize=3) / 8.0
            self.GY[k] = cv2.Sobel(d, cv2.CV_32F, 0, 1, ksize=3) / 8.0

    def lookup(self, uv, bins):
        """Nearest-pixel DT value and gradient; out-of-image -> tau, grad 0."""
        u = np.rint(uv[:, 0]).astype(np.int64)
        v = np.rint(uv[:, 1]).astype(np.int64)
        inside = (u >= 0) & (u < self.w) & (v >= 0) & (v < self.h)
        uc, vc = np.clip(u, 0, self.w - 1), np.clip(v, 0, self.h - 1)
        d = np.where(inside, self.DT[bins, vc, uc], self.tau)
        gx = np.where(inside, self.GX[bins, vc, uc], 0.0)
        gy = np.where(inside, self.GY[bins, vc, uc], 0.0)
        # sub-pixel: first-order correction from the gradient
        du, dv = uv[:, 0] - u, uv[:, 1] - v
        d = np.minimum(np.maximum(d + gx * du + gy * dv, 0.0), self.tau)
        return d, gx, gy, inside


def build_levels(img_bgr, K_full, work_scale=0.5):
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    l0 = cv2.resize(gray, None, fx=work_scale, fy=work_scale, interpolation=cv2.INTER_AREA)
    l1 = cv2.resize(l0, None, fx=0.5, fy=0.5, interpolation=cv2.INTER_AREA)
    S0 = np.diag([work_scale, work_scale, 1.0])
    S1 = np.diag([0.5, 0.5, 1.0])
    K0 = S0 @ K_full; K0[0, 2] = (K_full[0, 2] + 0.5) * work_scale - 0.5; K0[1, 2] = (K_full[1, 2] + 0.5) * work_scale - 0.5
    K1 = S1 @ K0; K1[0, 2] = (K0[0, 2] + 0.5) * 0.5 - 0.5; K1[1, 2] = (K0[1, 2] + 0.5) * 0.5 - 0.5
    levels = [Level(l0, K0, work_scale, 10.0), Level(l1, K1, work_scale * 0.5, 12.0, tau_coarse=30.0)]
    co = Level.__new__(Level)
    co.__dict__.update(levels[1].__dict__)
    co.DT, co.tau = levels[1].DTc, 30.0
    co.GX = co.GY = np.zeros_like(co.DT)
    levels[1].coarse = co
    c1 = cv2.resize(img_bgr, (levels[1].w, levels[1].h), interpolation=cv2.INTER_AREA)
    levels[1].lab = cv2.cvtColor(c1, cv2.COLOR_BGR2LAB).astype(np.float32)
    return levels


def region_score(lev, m, T_co):
    """Colour separation between the projected silhouette and a band around it
    (region-based cue; textureless white panels vs table). tanh-squashed, 0..1."""
    Vc = m.V @ T_co[:3, :3].T + T_co[:3, 3]
    Z = zbuffer(Vc, m.F, lev.K[0, 0], lev.K[1, 1], lev.K[0, 2], lev.K[1, 2], lev.w, lev.h)
    mask = np.isfinite(Z)
    if mask.sum() < 60:
        return 0.0
    mu8 = mask.astype(np.uint8)
    band = (cv2.dilate(mu8, np.ones((9, 9), np.uint8), iterations=1) > 0) & ~mask
    inner = cv2.erode(mu8, np.ones((3, 3), np.uint8)) > 0
    if band.sum() < 30 or inner.sum() < 30:
        return 0.0
    a, b = lev.lab[inner], lev.lab[band]
    sep = np.linalg.norm(a.mean(0) - b.mean(0)) / (a.std(0).mean() + b.std(0).mean() + 2.0)
    return float(np.tanh(sep))


def _proj(K, T_co, X):
    Xc = X @ T_co[:3, :3].T + T_co[:3, 3]
    z = np.maximum(Xc[:, 2:3], 1e-3)
    return Xc[:, :2] / z * [K[0, 0], K[1, 1]] + [K[0, 2], K[1, 2]]


def _bins(K, T_co, P, D):
    a = _proj(K, T_co, P)
    b = _proj(K, T_co, P + 0.005 * D)
    e = b - a
    nang = (np.degrees(np.arctan2(e[:, 1], e[:, 0])) + 90.0) % 180.0  # normal = gradient direction
    return np.floor(nang / (180.0 / NB)).astype(np.int64) % NB


def evaluate(level, m, T_cw, T_wo, idx):
    T_co = T_cw @ T_wo
    P, D = m.P[idx], m.D[idx]
    uv = _proj(level.K, T_co, P)
    bins = _bins(level.K, T_co, P, D)
    d, _, _, inside = level.lookup(uv, bins)
    return d, inside


INTERIOR_W = 0.3  # weight of interior creases (white-on-white, often invisible) vs occluding contours


def sample_weights(contour):
    return np.where(contour, 1.0, INTERIOR_W)


def fitness(level, m, T_cw, T_wo, idx, contour=None):
    """(weighted mean truncated cost in [0,1], weighted inlier ratio, n samples)."""
    if len(idx) < 20:
        return 1.0, 0.0, len(idx)
    wt = sample_weights(contour) if contour is not None else np.ones(len(idx))
    d, inside = evaluate(level, m, T_cw, T_wo, idx)
    c = float(np.sum(wt * (d / level.tau) ** 2) / wt.sum())
    inl = float(np.sum(wt * (d < 2.0)) / wt.sum())
    return c, inl, len(idx)


def lm(level, m, T_cw, T_wt, th0, idx, iters=12, prior=None, huber=2.0, wts=None):
    """LM over th (3 or 6 params). prior: (th_ref, sigmas) soft prior rows."""
    th = np.array(th0, float)
    nP = len(th)
    steps = np.array([1e-3, 1e-3, 2e-3, 1e-3, 2e-3, 2e-3])[:nP]
    P, D = m.P[idx], m.D[idx]
    lam = 1e-2
    wts = np.ones(len(idx)) if wts is None else wts

    def resid(t):
        T_co = T_cw @ pose_from_params(T_wt, t)
        uv = _proj(level.K, T_co, P)
        bins = _bins(level.K, T_co, P, D)
        d, gx, gy, inside = level.lookup(uv, bins)
        return T_co, uv, bins, d, gx, gy

    def total(d, t):
        c = np.where(d < huber, 0.5 * d ** 2, huber * (d - 0.5 * huber))
        s = float((wts * c).sum())
        if prior is not None:
            s += 0.5 * float((((t - prior[0]) / prior[1]) ** 2).sum()) * len(d) * 0.05
        return s

    T_co, uv, bins, d, gx, gy = resid(th)
    cost = total(d, th)
    for _ in range(iters):
        J = np.empty((len(P), nP))
        for j in range(nP):
            t2 = th.copy(); t2[j] += steps[j]
            uv2 = _proj(level.K, T_cw @ pose_from_params(T_wt, t2), P)
            duv = (uv2 - uv) / steps[j]
            J[:, j] = gx * duv[:, 0] + gy * duv[:, 1]
        w = np.where(d < huber, 1.0, huber / np.maximum(d, 1e-6))
        w = np.where(d >= level.tau - 1e-3, 0.0, w) * wts
        A = J.T @ (J * w[:, None])
        g = J.T @ (w * d)
        if prior is not None:
            pw = len(d) * 0.05 / prior[1] ** 2
            A += np.diag(pw)
            g += pw * (th - prior[0])
        improved = False
        for _ in range(4):
            try:
                delta = -np.linalg.solve(A + lam * np.diag(np.diag(A) + 1e-6), g)
            except np.linalg.LinAlgError:
                break
            t_new = th + delta
            r2 = resid(t_new)
            c_new = total(r2[3], t_new)
            if c_new < cost:
                th, cost = t_new, c_new
                T_co, uv, bins, d, gx, gy = r2
                lam = max(lam / 3, 1e-5)
                improved = True
                break
            lam *= 4
        if not improved or np.abs(delta[:3]).max() < 1e-5:
            break
    return th


class EdgeTracker:
    def __init__(self, model, T_world_table, six_dof=True, work_scale=0.5):
        self.m = model
        self.T_wt = T_world_table
        self.six = six_dof
        self.work_scale = work_scale
        self.th = None  # current estimate (6 params)
        self.lost = True
        self.cands = None
        self.init_n = 0
        self.bad = 0
        self.timing = {}

    # -- helpers ---------------------------------------------------------------
    def _vis(self, level, T_cw, th, zb=True, stride=1):
        T_co = T_cw @ pose_from_params(self.T_wt, th)
        idx, cont = visible_samples(self.m, level.K, T_co, level.w, level.h, use_zbuffer=zb)
        return idx[::stride], cont[::stride]

    def _refine(self, levels, T_cw, th, iters=(8, 10)):
        th = np.array(th, float)
        th3 = th[:3].copy()
        for lev, it in zip((levels[1], levels[0]), iters):
            idx, cont = self._vis(lev, T_cw, np.r_[th3, th[3:]], stride=2 if lev is levels[1] else 1)
            if len(idx) < 20:
                return np.r_[th3, th[3:]], 1.0, 0.0
            th3 = lm(lev, self.m, T_cw, self.T_wt, th3, idx, iters=it, wts=sample_weights(cont))
        th = np.r_[th3, th[3:]] if len(th) == 6 else th3
        if self.six:
            th6 = np.r_[th3, th[3:6]] if len(th) == 6 else np.r_[th3, 0.0, 0.0, 0.0]
            idx, cont = self._vis(levels[0], T_cw, th6)
            th6 = lm(levels[0], self.m, T_cw, self.T_wt, th6, idx, iters=8, wts=sample_weights(cont),
                     prior=(np.r_[th3, 0, 0, 0], np.array([0.02, 0.02, np.radians(5), 0.01, np.radians(3), np.radians(3)])))
            th = th6
        idx, cont = self._vis(levels[0], T_cw, th)
        c, inl, n = fitness(levels[0], self.m, T_cw, pose_from_params(self.T_wt, th), idx, cont)
        return th, c, inl

    def global_search(self, levels, T_cw, topk=16, yaw_step=10, grid_px=9):
        lev = levels[1].coarse
        T_wc = np.linalg.inv(T_cw)
        T_tw = np.linalg.inv(self.T_wt)
        # candidate positions: rays through a pixel grid hitting the table plane
        us, vs = np.meshgrid(np.arange(4, lev.w, grid_px), np.arange(4, lev.h, grid_px))
        rays = np.stack([(us.ravel() - lev.K[0, 2]) / lev.K[0, 0], (vs.ravel() - lev.K[1, 2]) / lev.K[1, 1],
                         np.ones(us.size)], 1)
        R_tc = (T_tw @ T_wc)[:3, :3]; o_t = (T_tw @ T_wc)[:3, 3]
        dt = rays @ R_tc.T
        s = -o_t[2] / np.where(np.abs(dt[:, 2]) < 1e-6, -1e-6, dt[:, 2])
        # assembling happens within arm's reach: object 0.25-1.2 m from the camera
        ok = (s > 0) & (np.linalg.norm(rays, axis=1) * s < MAX_RANGE)
        xy = (o_t[None] + s[:, None] * dt)[ok][:, :2]
        # drop the object's half height parallax: the ray hits the table, the object centre is above it
        yaws = np.radians(np.arange(0, 360, yaw_step))
        sub = np.arange(0, len(self.m.P), max(1, len(self.m.P) // 250))
        P, D, N1, N2 = self.m.P[sub], self.m.D[sub], self.m.N1[sub], self.m.N2[sub]
        best = []
        T_ct = T_cw @ self.T_wt
        Rct, tct = T_ct[:3, :3], T_ct[:3, 3]
        for yaw in yaws:
            Rz = rot_z(yaw)
            Pt = P @ Rz.T  # table frame, before translation
            Dt = D @ Rz.T
            n1 = N1 @ Rz.T @ Rct.T; n2 = N2 @ Rz.T @ Rct.T
            # cam-frame points for all positions: (H, N, 3)
            Xt = Pt[None] + np.c_[xy, np.zeros(len(xy))][:, None]
            Xc = Xt @ Rct.T + tct
            view = -Xc / np.linalg.norm(Xc, axis=2, keepdims=True)
            vis = (((n1[None] * view).sum(2) > 0.02) | ((n2[None] * view).sum(2) > 0.02)) & (Xc[..., 2] > 0.05)
            z = np.maximum(Xc[..., 2:3], 1e-3)
            uv = Xc[..., :2] / z * [lev.K[0, 0], lev.K[1, 1]] + [lev.K[0, 2], lev.K[1, 2]]
            Xc2 = (Xt + 0.005 * Dt[None]) @ Rct.T + tct
            uv2 = Xc2[..., :2] / np.maximum(Xc2[..., 2:3], 1e-3) * [lev.K[0, 0], lev.K[1, 1]] + [lev.K[0, 2], lev.K[1, 2]]
            e = uv2 - uv
            bins = (np.floor(((np.degrees(np.arctan2(e[..., 1], e[..., 0])) + 90) % 180) / (180 / NB)).astype(np.int64)) % NB
            d, _, _, inside = lev.lookup(uv.reshape(-1, 2), bins.ravel())
            d = d.reshape(vis.shape)
            inside = inside.reshape(vis.shape)
            nvis = vis.sum(1)
            # out-of-image visible samples count as misses
            # metric chamfer: a pixel residual means more at a larger distance, which removes
            # the bias towards far (small, easily matched) hypotheses in textured scenes
            dm = np.minimum(d * Xc[..., 2] / lev.K[0, 0], COARSE_TAU_M) / COARSE_TAU_M
            dm = np.where(inside, dm, 1.0)
            cost = (np.where(vis, dm ** 2, 0).sum(1)) / np.maximum(nvis, 1)
            frac_in = (vis & inside).sum(1) / np.maximum(nvis, 1)
            score = cost + 0.5 * (1 - frac_in) + (nvis < 30) * 1.0
            for k in np.argsort(score)[:topk]:
                best.append((score[k], xy[k, 0], xy[k, 1], yaw))
        best.sort(key=lambda b: b[0])
        # non-maximum suppression
        picked = []
        for sc, x, y, yaw in best:
            if all(np.hypot(x - p[1], y - p[2]) > 0.04 or abs(((yaw - p[3] + np.pi) % (2 * np.pi)) - np.pi) > np.radians(20)
                   for p in picked):
                picked.append((sc, x, y, yaw))
            if len(picked) >= topk:
                break
        results = []
        for sc, x, y, yaw in picked:
            th, c, inl = self._refine(levels, T_cw, [x, y, yaw])
            results.append([self.verify(levels, T_cw, th, c, inl), th, c, inl])
        results.sort(key=lambda r: -r[0])
        return results

    def verify(self, levels, T_cw, th, c, inl):
        rs = region_score(levels[1], self.m, T_cw @ pose_from_params(self.T_wt, th))
        return inl - c + REGION_W * rs

    # -- per frame -------------------------------------------------------------
    def step(self, img_bgr, K, T_world_cam):
        t0 = time.perf_counter()
        levels = build_levels(img_bgr, K, self.work_scale)
        t1 = time.perf_counter()
        T_cw = np.linalg.inv(T_world_cam)
        ok = False
        if self.cands is not None:
            # multi-frame init: the object is static in the world while the head moves,
            # so wrong hypotheses rarely keep fitting from new viewpoints
            mode = "init"
            for cd in self.cands:
                th, c, inl = self._refine(levels, T_cw, cd[1])
                cd[0] += self.verify(levels, T_cw, th, c, inl)
                cd[1], cd[2], cd[3] = th, c, inl
            self.init_n += 1
            self.cands.sort(key=lambda r: -r[0])
            best = self.cands[0]
            th, c, inl = best[1], best[2], best[3]
            if self.init_n >= INIT_FRAMES:
                ok = inl > OK_INL and c < OK_COST
                self.cands = None
                if ok:
                    self.th, self.lost = np.array(th), False
                else:
                    self.th = None
        elif self.th is not None:
            mode = "track"
            th, c, inl = self._refine(levels, T_cw, self.th)
            ok = inl > OK_INL and c < OK_COST
            if ok:
                self.th, self.lost, self.bad = np.array(th), False, 0
            else:
                # occlusion / blur: hold the last pose for a while before re-initialising
                mode = "hold"
                self.bad += 1
                th = self.th
                if self.bad > HOLD_FRAMES:
                    self.th, self.lost = None, True
        else:
            mode = "init"
            self.cands = self.global_search(levels, T_cw)[:INIT_KEEP]
            self.init_n = 1
            best = self.cands[0]
            th, c, inl = best[1], best[2], best[3]
        t2 = time.perf_counter()
        self.timing = {"prep_ms": (t1 - t0) * 1e3, "solve_ms": (t2 - t1) * 1e3}
        return pose_from_params(self.T_wt, th), {
            "mode": mode, "ok": bool(ok), "cost": float(c), "inliers": float(inl), **self.timing}

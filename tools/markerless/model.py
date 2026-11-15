"""Model side of the markerless tracker: state meshes, sharp-edge samples,
projection and a small numba z-buffer rasteriser for edge visibility."""
import functools
import os

import numpy as np
from numba import njit

HERE = os.path.dirname(os.path.abspath(__file__))
SHARP_DEG = 30.0


def read_ply(path):
    with open(path) as fh:
        nv = nf = 0
        while True:
            line = fh.readline().strip()
            if line.startswith("element vertex"):
                nv = int(line.split()[-1])
            elif line.startswith("element face"):
                nf = int(line.split()[-1])
            elif line == "end_header":
                break
        V = np.loadtxt(fh, max_rows=nv, dtype=np.float64)
        F = np.loadtxt(fh, max_rows=nf, dtype=np.int64)[:, 1:4]
    return V, F


class Model:
    def __init__(self, V, F, sample_mm=5.0):
        # weld duplicated vertices so edges are shared between faces
        key = np.round(V / 1e-5).astype(np.int64)
        _, inv, uniq_idx = None, None, None
        uniq, idx, inv = np.unique(key, axis=0, return_index=True, return_inverse=True)
        self.V = V[idx]
        F = inv.reshape(-1)[F]
        F = F[(F[:, 0] != F[:, 1]) & (F[:, 1] != F[:, 2]) & (F[:, 0] != F[:, 2])]
        self.F = F
        a, b, c = self.V[F[:, 0]], self.V[F[:, 1]], self.V[F[:, 2]]
        n = np.cross(b - a, c - a)
        area = np.linalg.norm(n, axis=1)
        keep = area > 1e-12
        self.F, n, area = F[keep], n[keep], area[keep]
        self.FN = n / area[:, None]
        # edge -> faces
        E = np.concatenate([self.F[:, [0, 1]], self.F[:, [1, 2]], self.F[:, [2, 0]]])
        fid = np.tile(np.arange(len(self.F)), 3)
        Es = np.sort(E, axis=1)
        order = np.lexsort((Es[:, 1], Es[:, 0]))
        Es, fid = Es[order], fid[order]
        edges, n1, n2 = [], [], []
        i = 0
        while i < len(Es):
            j = i
            while j + 1 < len(Es) and Es[j + 1, 0] == Es[i, 0] and Es[j + 1, 1] == Es[i, 1]:
                j += 1
            fs = fid[i:j + 1]
            if len(fs) == 1:
                edges.append(Es[i]); n1.append(self.FN[fs[0]]); n2.append(-self.FN[fs[0]])
            else:
                nn = self.FN[fs]
                cosang = np.clip(nn[0] @ nn[1:].T, -1, 1)
                k = int(np.argmin(cosang)) + 1
                if np.degrees(np.arccos(cosang[k - 1])) > SHARP_DEG:
                    edges.append(Es[i]); n1.append(nn[0]); n2.append(nn[k])
            i = j + 1
        self.edges = np.array(edges)
        n1, n2 = np.array(n1), np.array(n2)
        # sample points along sharp edges
        P, D, N1, N2 = [], [], [], []
        for (ia, ib), na, nb in zip(self.edges, n1, n2):
            pa, pb = self.V[ia], self.V[ib]
            L = np.linalg.norm(pb - pa)
            if L < 2e-3:
                continue
            k = max(1, int(L * 1000 / sample_mm))
            t = (np.arange(k) + 0.5) / k
            P.append(pa + t[:, None] * (pb - pa))
            D.append(np.repeat(((pb - pa) / L)[None], k, 0))
            N1.append(np.repeat(na[None], k, 0)); N2.append(np.repeat(nb[None], k, 0))
        self.P, self.D = np.concatenate(P), np.concatenate(D)
        self.N1, self.N2 = np.concatenate(N1), np.concatenate(N2)
        self.size = self.V.max(0) - self.V.min(0)


@functools.lru_cache(maxsize=None)
def load_state(n, scale=(1.0, 1.0, 1.0)):
    V, F = read_ply(os.path.join(HERE, "models", f"state_{n:02d}.ply"))
    return Model(V * np.array(scale), F)


def project(K, T_co, X):
    Xc = X @ T_co[:3, :3].T + T_co[:3, 3]
    uv = Xc[:, :2] / Xc[:, 2:3]
    return uv * [K[0, 0], K[1, 1]] + [K[0, 2], K[1, 2]]


@njit(cache=True)
def zbuffer(Xc, F, fx, fy, cx, cy, w, h):
    """Depth buffer of the camera-frame vertices Xc (z forward)."""
    Z = np.full((h, w), np.inf)
    for t in range(F.shape[0]):
        i0, i1, i2 = F[t, 0], F[t, 1], F[t, 2]
        z0, z1, z2 = Xc[i0, 2], Xc[i1, 2], Xc[i2, 2]
        if z0 < 1e-3 or z1 < 1e-3 or z2 < 1e-3:
            continue
        u0 = fx * Xc[i0, 0] / z0 + cx; v0 = fy * Xc[i0, 1] / z0 + cy
        u1 = fx * Xc[i1, 0] / z1 + cx; v1 = fy * Xc[i1, 1] / z1 + cy
        u2 = fx * Xc[i2, 0] / z2 + cx; v2 = fy * Xc[i2, 1] / z2 + cy
        area = (u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0)
        if abs(area) < 1e-9:
            continue
        xmin = max(int(np.floor(min(u0, u1, u2))), 0); xmax = min(int(np.ceil(max(u0, u1, u2))), w - 1)
        ymin = max(int(np.floor(min(v0, v1, v2))), 0); ymax = min(int(np.ceil(max(v0, v1, v2))), h - 1)
        for y in range(ymin, ymax + 1):
            for x in range(xmin, xmax + 1):
                px, py = x + 0.5, y + 0.5
                w0 = ((u1 - px) * (v2 - py) - (u2 - px) * (v1 - py)) / area
                w1 = ((u2 - px) * (v0 - py) - (u0 - px) * (v2 - py)) / area
                w2 = 1.0 - w0 - w1
                if w0 < 0 or w1 < 0 or w2 < 0:
                    continue
                z = w0 * z0 + w1 * z1 + w2 * z2
                if z < Z[y, x]:
                    Z[y, x] = z
    return Z


def visible_samples(m, K, T_co, w, h, zb_scale=0.5, use_zbuffer=True, eps=0.006):
    """Indices of edge samples that would show as image edges from this pose."""
    R, t = T_co[:3, :3], T_co[:3, 3]
    Pc = m.P @ R.T + t
    view = -Pc / np.linalg.norm(Pc, axis=1, keepdims=True)  # towards camera
    f1 = (m.N1 @ R.T * view).sum(1) > 0.02
    f2 = (m.N2 @ R.T * view).sum(1) > 0.02
    ok = (f1 | f2) & (Pc[:, 2] > 0.05)
    if use_zbuffer:
        sw, sh = int(w * zb_scale), int(h * zb_scale)
        Vc = m.V @ R.T + t
        Z = zbuffer(Vc, m.F, K[0, 0] * zb_scale, K[1, 1] * zb_scale, K[0, 2] * zb_scale, K[1, 2] * zb_scale, sw, sh)
        uv = Pc[:, :2] / Pc[:, 2:3] * [K[0, 0] * zb_scale, K[1, 1] * zb_scale] + [K[0, 2] * zb_scale, K[1, 2] * zb_scale]
        ui = np.clip(uv[:, 0].astype(int), 0, sw - 1); vi = np.clip(uv[:, 1].astype(int), 0, sh - 1)
        # min over a 3x3 neighbourhood is too strict at silhouettes; compare to the max instead
        zl = Z[vi, ui]
        for du, dv in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            zl = np.maximum(zl, np.where(np.isinf(Z[np.clip(vi + dv, 0, sh - 1), np.clip(ui + du, 0, sw - 1)]), np.inf,
                                         Z[np.clip(vi + dv, 0, sh - 1), np.clip(ui + du, 0, sw - 1)]))
        ok &= Pc[:, 2] <= zl + eps
        # occluding contour: across the projected edge normal, one side is
        # background (no object depth) or much farther away -> reliable edge
        tip = (Pc + 0.005 * (m.D @ R.T))
        uv2 = tip[:, :2] / tip[:, 2:3] * [K[0, 0] * zb_scale, K[1, 1] * zb_scale] + [K[0, 2] * zb_scale, K[1, 2] * zb_scale]
        e = uv2 - uv
        nrm = np.stack([-e[:, 1], e[:, 0]], 1) / np.maximum(np.linalg.norm(e, axis=1, keepdims=True), 1e-9)
        contour = np.zeros(len(Pc), bool)
        for sgn in (1, -1):
            q = uv + sgn * 2.5 * nrm
            qu = np.clip(q[:, 0].astype(int), 0, sw - 1); qv = np.clip(q[:, 1].astype(int), 0, sh - 1)
            zq = Z[qv, qu]
            contour |= np.isinf(zq) | (zq > Pc[:, 2] + 0.03)
        idx = np.nonzero(ok)[0]
        return idx, contour[idx]
    idx = np.nonzero(ok)[0]
    return idx, np.ones(len(idx), bool)

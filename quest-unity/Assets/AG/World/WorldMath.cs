using System.Collections.Generic;
using AG.Vision;
using UnityEngine;

namespace AG.World
{
    /// <summary>
    /// A horizontal work surface (table or desk top) in Unity world space: a rectangle at height
    /// <see cref="TopY"/>, centred on <see cref="Centre"/>, spanned by two horizontal unit axes.
    /// </summary>
    public struct TableSurface
    {
        /// <summary>Centre of the top face, world.</summary>
        public Vector3 Centre;
        /// <summary>Horizontal unit vectors along the two edges.</summary>
        public Vector3 AxisA, AxisB;
        /// <summary>Half lengths along <see cref="AxisA"/> and <see cref="AxisB"/>, metres.</summary>
        public float HalfA, HalfB;

        public float TopY => Centre.y;

        /// <summary>
        /// The point of the top face closest to <paramref name="p"/> (heights ignored), kept
        /// <paramref name="margin"/> metres inside the edges (less on a table narrower than twice the margin).
        /// </summary>
        public Vector3 Clamp(Vector3 p, float margin = 0f)
        {
            var d = p - Centre;
            float ma = Mathf.Min(margin, HalfA * 0.5f), mb = Mathf.Min(margin, HalfB * 0.5f);
            float a = Mathf.Clamp(Vector3.Dot(d, AxisA), -(HalfA - ma), HalfA - ma);
            float b = Mathf.Clamp(Vector3.Dot(d, AxisB), -(HalfB - mb), HalfB - mb);
            return Centre + AxisA * a + AxisB * b;
        }

        /// <summary>True when <paramref name="p"/> is over the top face (heights ignored).</summary>
        public bool Contains(Vector3 p, float margin = 0f)
        {
            var d = p - Centre;
            return Mathf.Abs(Vector3.Dot(d, AxisA)) <= HalfA - margin && Mathf.Abs(Vector3.Dot(d, AxisB)) <= HalfB - margin;
        }

        /// <summary>
        /// From a scene anchor: its pose and its box (or flat rectangle) in the anchor's local frame, unit scale.
        /// Whichever local axis points along world up is taken as the vertical one, so this does not depend on the
        /// scene API's axis convention. False when no local axis is within about 25 degrees of vertical (a wall, a
        /// slanted board) or the surface has no area.
        /// </summary>
        public static bool TryFromLocalBounds(Pose anchor, Bounds local, out TableSurface table)
        {
            table = default;
            var axes = new[] { anchor.rotation * Vector3.right, anchor.rotation * Vector3.up, anchor.rotation * Vector3.forward };
            int v = 0;
            for (int i = 1; i < 3; i++)
                if (Mathf.Abs(axes[i].y) > Mathf.Abs(axes[v].y)) v = i;
            if (Mathf.Abs(axes[v].y) < 0.9f) return false;
            int ia = (v + 1) % 3, ib = (v + 2) % 3;
            var a = Vector3.ProjectOnPlane(axes[ia], Vector3.up);
            var b = Vector3.ProjectOnPlane(axes[ib], Vector3.up);
            if (a.sqrMagnitude < 1e-6f || b.sqrMagnitude < 1e-6f) return false;
            var c = anchor.position + anchor.rotation * local.center;
            c.y += local.extents[v] * Mathf.Abs(axes[v].y);
            table = new TableSurface { Centre = c, AxisA = a.normalized, AxisB = b.normalized, HalfA = local.extents[ia], HalfB = local.extents[ib] };
            return table.HalfA > 0.05f && table.HalfB > 0.05f;
        }
    }

    /// <summary>
    /// Pure geometry of the world layer: camera pixel -> world ray -> table plane, and where the assembly anchor goes
    /// when nothing tracks the real object. No scene, no headset: covered by EditMode tests.
    /// </summary>
    public static class WorldMath
    {
        /// <summary>Head fallback: this far ahead of the head (horizontally) and this far below the eyes.</summary>
        public const float AheadM = 0.55f, BelowEyeM = 0.35f;
        /// <summary>Keep the anchor this far inside the table edge.</summary>
        public const float EdgeMarginM = 0.12f;
        /// <summary>A table counts when its top is between these distances below the eyes (seated to standing).</summary>
        public const float MinDropM = 0.15f, MaxDropM = 1.5f;
        /// <summary>A table counts when its nearest usable point is within this horizontal distance of the head.</summary>
        public const float MaxTableReachM = 1.5f;

        /// <summary>
        /// The head's forward direction on the floor plane. Looking straight down (or up) the forward vector has no
        /// horizontal part; the top of the head then points the way the user faces (or away from it).
        /// </summary>
        public static Vector3 HorizontalForward(Pose head)
        {
            var f = Vector3.ProjectOnPlane(head.forward, Vector3.up);
            if (f.sqrMagnitude < 1e-4f)
            {
                f = Vector3.ProjectOnPlane(head.up, Vector3.up) * (head.forward.y < 0 ? 1f : -1f);
                if (f.sqrMagnitude < 1e-6f) f = Vector3.forward;
            }
            return f.normalized;
        }

        /// <summary>
        /// Anchor pose when the room is unknown: <see cref="AheadM"/> ahead of the head, <see cref="BelowEyeM"/>
        /// below the eyes, upright, turned so the assembly's -Z side (the front of the product: the app's existing
        /// convention, +Z away from the user) faces the user.
        /// </summary>
        public static Pose HeadFallback(Pose head)
        {
            var fwd = HorizontalForward(head);
            return new Pose(head.position + fwd * AheadM + Vector3.down * BelowEyeM, Quaternion.LookRotation(fwd, Vector3.up));
        }

        /// <summary>Upright pose at <paramref name="point"/> whose -Z side faces the head (same convention as <see cref="HeadFallback"/>).</summary>
        public static Pose FacingUser(Vector3 point, Pose head)
        {
            var away = Vector3.ProjectOnPlane(point - head.position, Vector3.up);
            if (away.sqrMagnitude < 0.05f * 0.05f) away = HorizontalForward(head);
            return new Pose(point, Quaternion.LookRotation(away.normalized, Vector3.up));
        }

        /// <summary>
        /// Where on <paramref name="table"/> the anchor goes: the spot the user looks at when the gaze meets the
        /// table top (looking down by 10 degrees or more), otherwise the point of the table nearest to
        /// <see cref="AheadM"/> ahead of the head; always <see cref="EdgeMarginM"/> inside the edges.
        /// </summary>
        public static Vector3 PointOnTable(TableSurface table, Pose head)
        {
            var target = head.position + HorizontalForward(head) * AheadM;
            if (head.forward.y < -0.17f
                && RayPlane(new Ray(head.position, head.forward), table.Centre, Vector3.up, out var hit, 3f)
                && table.Contains(hit))
                target = hit;
            var p = table.Clamp(target, EdgeMarginM);
            p.y = table.TopY;
            return p;
        }

        /// <summary>
        /// The table to work on and the anchor pose on it: among tables at a plausible height and within reach, the one
        /// whose usable point is closest to where the user looks. False when there is none (use <see cref="HeadFallback"/>).
        /// </summary>
        public static bool TryPlaceOnTables(IReadOnlyList<TableSurface> tables, Pose head, out Pose place, out int index)
        {
            place = default;
            index = -1;
            if (tables == null) return false;
            var want = head.position + HorizontalForward(head) * AheadM;
            float best = float.MaxValue;
            for (int i = 0; i < tables.Count; i++)
            {
                float drop = head.position.y - tables[i].TopY;
                if (drop < MinDropM || drop > MaxDropM) continue;
                var p = PointOnTable(tables[i], head);
                if (HorizontalDistance(p, head.position) > MaxTableReachM) continue;
                float score = HorizontalDistance(p, want);
                if (score < best) { best = score; index = i; place = FacingUser(p, head); }
            }
            return index >= 0;
        }

        /// <summary>
        /// The long axis of a server box on the table (from <see cref="BoxOnTable"/>'s size and across), or null when
        /// the box is not clearly elongated. The picture's box is upright in the picture, so a part lying askew gives
        /// a squarish box and no axis: with <paramref name="minRatio"/> 2 a 380 x 130 mm board passes only within
        /// about 10 degrees of the picture's axes.
        /// </summary>
        public static Vector3? LongAxis(Vector2 size, Vector3 across, float minRatio = 2f)
        {
            var a = Vector3.ProjectOnPlane(across, Vector3.up);
            if (size.x <= 0 || size.y <= 0 || a.sqrMagnitude < 1e-6f) return null;
            a.Normalize();
            if (size.x >= size.y * minRatio) return a;
            if (size.y >= size.x * minRatio) return Vector3.Cross(Vector3.up, a);
            return null;
        }

        /// <summary>
        /// The anchor pose that puts one part of the assembly on a point of the table. <paramref name="partCentre"/>
        /// and <paramref name="partLongAxis"/> are that part's centre and long edge in world NOW (under
        /// <paramref name="anchor"/>); the result moves the centre over <paramref name="target"/> (horizontally only;
        /// the height is left to the caller) and, when <paramref name="tableLongAxis"/> is given and the part's long
        /// edge is not standing up, turns about the vertical through the part so the two axes line up. An axis has no
        /// direction, so the smaller of the two possible turns is taken (at most 90 degrees).
        /// </summary>
        public static Pose AlignAnchor(Pose anchor, Vector3 partCentre, Vector3 partLongAxis, Vector3 target, Vector3? tableLongAxis)
        {
            float yaw = 0f;
            if (tableLongAxis.HasValue)
            {
                var a = Vector3.ProjectOnPlane(partLongAxis, Vector3.up);
                var b = Vector3.ProjectOnPlane(tableLongAxis.Value, Vector3.up);
                if (a.sqrMagnitude > 0.5f * partLongAxis.sqrMagnitude && b.sqrMagnitude > 1e-6f)
                {
                    yaw = Vector3.SignedAngle(a, b, Vector3.up);
                    if (yaw > 90f) yaw -= 180f;
                    else if (yaw < -90f) yaw += 180f;
                }
            }
            var turn = Quaternion.AngleAxis(yaw, Vector3.up);
            var pos = partCentre + turn * (anchor.position - partCentre);
            var slide = target - partCentre;
            slide.y = 0f;
            return new Pose(pos + slide, turn * anchor.rotation);
        }

        /// <summary>
        /// Outline of a set of points seen from above: their convex hull on the floor plane, counter-clockwise seen
        /// from above, every point at height <paramref name="y"/>. For the eight corners of a box this is the
        /// box's footprint (a rectangle when it stands straight, up to a hexagon when it leans).
        /// </summary>
        public static List<Vector3> FootprintHull(IReadOnlyList<Vector3> points, float y)
        {
            var hull = new List<Vector3>();
            if (points == null || points.Count == 0) return hull;
            var p = new List<Vector3>(points);
            p.Sort((u, v) => u.x != v.x ? u.x.CompareTo(v.x) : u.z.CompareTo(v.z));
            // monotone chain; a turn is kept only when it is clearly a corner (1e-6 m2), so doubled corners drop out
            float Cross(Vector3 o, Vector3 a, Vector3 b) => (a.x - o.x) * (b.z - o.z) - (a.z - o.z) * (b.x - o.x);
            for (int pass = 0; pass < 2; pass++)
            {
                int start = hull.Count;
                for (int n = 0; n < p.Count; n++)
                {
                    var q = p[pass == 0 ? n : p.Count - 1 - n];
                    while (hull.Count - start >= 2 && Cross(hull[hull.Count - 2], hull[hull.Count - 1], q) <= 1e-6f) hull.RemoveAt(hull.Count - 1);
                    hull.Add(q);
                }
                hull.RemoveAt(hull.Count - 1);
            }
            for (int i = 0; i < hull.Count; i++) hull[i] = new Vector3(hull[i].x, y, hull[i].z);
            return hull;
        }

        public static float HorizontalDistance(Vector3 a, Vector3 b)
        {
            float dx = a.x - b.x, dz = a.z - b.z;
            return Mathf.Sqrt(dx * dx + dz * dz);
        }

        /// <summary>
        /// Centre of a server box (<c>box_2d</c> = [ymin, xmin, ymax, xmax], 0-1000 over the picture, y DOWN from the
        /// top edge) as a pixel of a <paramref name="width"/> x <paramref name="height"/> image, top-left origin, v down
        /// (the convention of <see cref="CameraIntrinsics"/>). The box is normalised, so the JPEG's own size does not matter.
        /// </summary>
        public static bool BoxCentrePixel(IReadOnlyList<int> box, int width, int height, out Vector2 pixel)
        {
            pixel = default;
            if (box == null || box.Count != 4 || width <= 0 || height <= 0) return false;
            pixel = NormToPixel((box[1] + box[3]) * 0.5f, (box[0] + box[2]) * 0.5f, width, height);
            return true;
        }

        /// <summary>A point in 0-1000 picture units (x right, y down) as a pixel (u right, v down).</summary>
        public static Vector2 NormToPixel(float x1000, float y1000, int width, int height)
            => new Vector2(x1000 / 1000f * width, y1000 / 1000f * height);

        /// <summary>
        /// World ray through pixel (u, v) (top-left origin, v down) of a camera with pinhole intrinsics
        /// <paramref name="k"/> at <paramref name="cameraPose"/> (Unity world, +Z the optical axis, +Y up). A larger v
        /// is lower in the picture, so the ray leans towards the camera's -Y.
        /// </summary>
        public static Ray PixelRay(CameraIntrinsics k, Pose cameraPose, float u, float v)
            => new Ray(cameraPose.position, (cameraPose.rotation * k.PixelToCameraDir(u, v)).normalized);

        /// <summary>
        /// Ray against a plane. False when the ray is (nearly) parallel to it, points away from it, or meets it
        /// farther than <paramref name="maxDistM"/>.
        /// </summary>
        public static bool RayPlane(Ray ray, Vector3 planePoint, Vector3 planeNormal, out Vector3 hit, float maxDistM = 5f)
        {
            hit = default;
            float denom = Vector3.Dot(ray.direction, planeNormal);
            if (Mathf.Abs(denom) < 1e-4f) return false;
            float t = Vector3.Dot(planePoint - ray.origin, planeNormal) / denom;
            if (t <= 0f || t > maxDistM) return false;
            hit = ray.origin + ray.direction * t;
            return true;
        }

        /// <summary>
        /// Where a server box lies on the table: the ray through the box centre meets the horizontal plane at
        /// <paramref name="tableY"/>. <paramref name="size"/> is the box's extent on the table (x: across the picture,
        /// y: up the picture), from the rays through the middles of its four edges; zero where an edge ray misses.
        /// <paramref name="across"/> is the world direction of the picture's left-to-right on the table.
        /// </summary>
        public static bool BoxOnTable(IReadOnlyList<int> box, CameraIntrinsics k, Pose cameraPose, float tableY,
            out Vector3 centre, out Vector2 size, out Vector3 across, float maxDistM = 3f)
        {
            centre = default; size = default; across = Vector3.right;
            if (!k.IsValid || !BoxCentrePixel(box, k.width, k.height, out var c)) return false;
            var plane = new Vector3(0, tableY, 0);
            if (!RayPlane(PixelRay(k, cameraPose, c.x, c.y), plane, Vector3.up, out centre, maxDistM)) return false;
            float cx = (box[1] + box[3]) * 0.5f, cy = (box[0] + box[2]) * 0.5f;
            var l = NormToPixel(box[1], cy, k.width, k.height);
            var r = NormToPixel(box[3], cy, k.width, k.height);
            var t = NormToPixel(cx, box[0], k.width, k.height);
            var b = NormToPixel(cx, box[2], k.width, k.height);
            if (RayPlane(PixelRay(k, cameraPose, l.x, l.y), plane, Vector3.up, out var pl, maxDistM * 2)
                && RayPlane(PixelRay(k, cameraPose, r.x, r.y), plane, Vector3.up, out var pr, maxDistM * 2))
            {
                size.x = Vector3.Distance(pl, pr);
                if (size.x > 1e-4f) across = (pr - pl) / size.x;
            }
            if (RayPlane(PixelRay(k, cameraPose, t.x, t.y), plane, Vector3.up, out var pt, maxDistM * 2)
                && RayPlane(PixelRay(k, cameraPose, b.x, b.y), plane, Vector3.up, out var pb, maxDistM * 2))
                size.y = Vector3.Distance(pt, pb);
            return true;
        }
    }

    /// <summary>
    /// Lets a new pose of the real object through only when it is worth a visible move, so the ghost never jitters:
    /// the first pose (or a forced one) at once; later ones only when they differ from the current pose by more than
    /// <see cref="MoveM"/> or <see cref="TurnDeg"/> AND keep saying the same thing (within <see cref="AgreeM"/> /
    /// <see cref="AgreeDeg"/>) for <see cref="HoldS"/>. Pure: the caller supplies the clock.
    /// </summary>
    public sealed class PoseGate
    {
        public float MoveM = 0.10f, TurnDeg = 20f, AgreeM = 0.05f, AgreeDeg = 15f, HoldS = 3f;

        /// <summary>True once a pose went through.</summary>
        public bool HasPose { get; private set; }

        Pose _pending;
        float _since;
        bool _hasPending;

        public void Reset() { HasPose = false; _hasPending = false; }

        /// <summary>True: apply <paramref name="candidate"/> now.</summary>
        public bool Offer(Pose current, Pose candidate, float nowS, bool force = false)
        {
            if (force || !HasPose) { HasPose = true; _hasPending = false; return true; }
            if (Close(current, candidate, MoveM, TurnDeg)) { _hasPending = false; return false; }
            if (_hasPending && Close(_pending, candidate, AgreeM, AgreeDeg))
            {
                if (nowS - _since < HoldS) return false;
                _hasPending = false;
                return true;
            }
            _pending = candidate;
            _since = nowS;
            _hasPending = true;
            return false;
        }

        public static bool Close(Pose a, Pose b, float m, float deg)
            => Vector3.Distance(a.position, b.position) <= m && Quaternion.Angle(a.rotation, b.rotation) <= deg;
    }

    /// <summary>What the placement does this frame.</summary>
    public enum PlaceDecision
    {
        /// <summary>Leave the anchor alone.</summary>
        None,
        /// <summary>The user has not started: the anchor may follow the head freely (nothing is shown yet).</summary>
        Provisional,
        /// <summary>The user starts now: place once and keep it.</summary>
        Place,
        /// <summary>Place again: asked for, or the anchor was left behind.</summary>
        Replace,
    }

    /// <summary>
    /// When the anchor may be (re)placed. Rules: a real track (marker, two corners) always wins and, once there has
    /// been one, only an explicit request places again; before the user starts the placement is provisional; after
    /// that it stays put unless the anchor is farther than <see cref="FarM"/> for <see cref="FarForS"/> or outside
    /// the view cone for <see cref="OutOfViewForS"/>. Pure state machine: no scene, no clock of its own.
    /// </summary>
    public sealed class PlacementPolicy
    {
        public float FarM = 1.5f, FarForS = 3f, ViewHalfAngleDeg = 55f, OutOfViewForS = 6f;

        public bool Placed { get; private set; }
        public bool EverTracked { get; private set; }
        /// <summary>The anchor sits on the real object (camera, markerless or manual pose): no automatic re-placing.</summary>
        public bool Pinned { get; private set; }
        /// <summary>Why the last <see cref="PlaceDecision.Place"/> / <see cref="PlaceDecision.Replace"/> was decided: first, request, far, out_of_view.</summary>
        public string Reason { get; private set; } = "";

        float _farS, _outS;
        bool _request;

        /// <summary>The user (or the app) asks for a new placement; honoured on the next step without a track.</summary>
        public void Request() => _request = true;

        /// <summary>A pose of the real object was applied: from now on only <see cref="Request"/> places again.</summary>
        public void Pin() { Pinned = true; Placed = true; _farS = _outS = 0f; }

        public PlaceDecision Step(bool tracked, bool started, Pose head, Vector3 anchor, float dt)
        {
            if (tracked)
            {
                EverTracked = true;
                _request = false;
                _farS = _outS = 0f;
                return PlaceDecision.None;
            }
            if (_request)
            {
                _request = false;
                Pinned = false;
                return Decide("request", PlaceDecision.Replace);
            }
            if (EverTracked || Pinned) return PlaceDecision.None;
            if (!started) return PlaceDecision.Provisional;
            if (!Placed) return Decide("first", PlaceDecision.Place);

            _farS = WorldMath.HorizontalDistance(head.position, anchor) > FarM ? _farS + dt : 0f;
            _outS = Vector3.Angle(head.forward, anchor - head.position) > ViewHalfAngleDeg ? _outS + dt : 0f;
            if (_farS >= FarForS) return Decide("far", PlaceDecision.Replace);
            if (_outS >= OutOfViewForS) return Decide("out_of_view", PlaceDecision.Replace);
            return PlaceDecision.None;
        }

        PlaceDecision Decide(string reason, PlaceDecision d)
        {
            Reason = reason;
            Placed = true;
            _farS = _outS = 0f;
            return d;
        }
    }
}

using System.Collections.Generic;
using AG.Guide;
using AG.Vision;
using NUnit.Framework;
using UnityEngine;

namespace AG.World.Tests
{
    /// <summary>
    /// Pure geometry of the world layer: pixel -> ray -> table plane (image y down against Unity y up, camera pose),
    /// table choice and head fallback, the placement rules, the no-jitter gate, anchor alignment, footprint outline.
    /// No scene, no headset.
    /// </summary>
    public class WorldMathTests
    {
        static readonly CameraIntrinsics K = new CameraIntrinsics { fx = 800, fy = 800, cx = 640, cy = 480, width = 1280, height = 960 };

        static void AssertVec(Vector3 want, Vector3 got, float tol = 1e-4f, string what = "")
            => Assert.That(Vector3.Distance(want, got), Is.LessThan(tol), $"{what} want {want:F4} got {got:F4}");

        /// <summary>Pinhole projection of a world point into pixels (u right, v down): the inverse of WorldMath.PixelRay.</summary>
        static Vector2 Project(Pose cam, Vector3 world)
        {
            var l = Quaternion.Inverse(cam.rotation) * (world - cam.position);
            return new Vector2(K.cx + K.fx * l.x / l.z, K.cy - K.fy * l.y / l.z);
        }

        /// <summary>Server box [ymin, xmin, ymax, xmax] in 0-1000 around a pixel rectangle.</summary>
        static int[] Box(Vector2 min, Vector2 max) => new[]
        {
            Mathf.RoundToInt(min.y / K.height * 1000), Mathf.RoundToInt(min.x / K.width * 1000),
            Mathf.RoundToInt(max.y / K.height * 1000), Mathf.RoundToInt(max.x / K.width * 1000),
        };

        // ---- pixel -> ray ------------------------------------------------------------------------------------

        [Test]
        public void PixelRay_PrincipalPoint_IsTheOpticalAxis()
        {
            var r = WorldMath.PixelRay(K, Pose.identity, K.cx, K.cy);
            AssertVec(Vector3.zero, r.origin);
            AssertVec(Vector3.forward, r.direction);
        }

        [Test]
        public void PixelRay_ImageYDown_IsUnityYDown_AndXRightStaysRight()
        {
            var below = WorldMath.PixelRay(K, Pose.identity, K.cx, K.cy + 100).direction;     // lower in the picture
            AssertVec(new Vector3(0, -0.125f, 1).normalized, below);
            var above = WorldMath.PixelRay(K, Pose.identity, K.cx, K.cy - 100).direction;
            Assert.That(above.y, Is.GreaterThan(0));
            var right = WorldMath.PixelRay(K, Pose.identity, K.cx + 100, K.cy).direction;
            AssertVec(new Vector3(0.125f, 0, 1).normalized, right);
        }

        [Test]
        public void PixelRay_UsesTheCameraPose()
        {
            var cam = new Pose(new Vector3(1, 2, 3), Quaternion.Euler(0, 90, 0));              // looks along +X
            var r = WorldMath.PixelRay(K, cam, K.cx, K.cy);
            AssertVec(new Vector3(1, 2, 3), r.origin);
            AssertVec(Vector3.right, r.direction);
            // the picture's right is the camera's right: for a camera looking along +X that is world -Z (left-handed)
            Assert.That(WorldMath.PixelRay(K, cam, K.cx + 100, K.cy).direction.z, Is.LessThan(-0.1f));
            Assert.That(WorldMath.PixelRay(K, cam, K.cx, K.cy + 100).direction.y, Is.LessThan(-0.1f));
        }

        [Test]
        public void BoxCentrePixel_ReadsYminXminYmaxXmax()
        {
            Assert.IsTrue(WorldMath.BoxCentrePixel(new[] { 200, 100, 400, 300 }, 1280, 960, out var p));
            Assert.AreEqual(0.2f * 1280, p.x, 1e-3f);      // x from entries 1 and 3
            Assert.AreEqual(0.3f * 960, p.y, 1e-3f);       // y from entries 0 and 2, down from the top
            Assert.IsFalse(WorldMath.BoxCentrePixel(new[] { 1, 2, 3 }, 1280, 960, out _));
            Assert.IsFalse(WorldMath.BoxCentrePixel(null, 1280, 960, out _));
        }

        // ---- ray -> plane ------------------------------------------------------------------------------------

        [Test]
        public void RayPlane_HitsAhead_NotBehind_NotParallel()
        {
            Assert.IsTrue(WorldMath.RayPlane(new Ray(new Vector3(0, 1, 0), Vector3.down), Vector3.zero, Vector3.up, out var hit));
            AssertVec(Vector3.zero, hit);
            Assert.IsFalse(WorldMath.RayPlane(new Ray(new Vector3(0, 1, 0), Vector3.up), Vector3.zero, Vector3.up, out _));
            Assert.IsFalse(WorldMath.RayPlane(new Ray(new Vector3(0, 1, 0), Vector3.forward), Vector3.zero, Vector3.up, out _));
            Assert.IsFalse(WorldMath.RayPlane(new Ray(new Vector3(0, 10, 0), Vector3.down), Vector3.zero, Vector3.up, out _, 5f));
        }

        [Test]
        public void BoxOnTable_RoundTrip_ThroughAPosedCamera()
        {
            // a camera at head height, turned 30 degrees to the right, looking 40 degrees down at a table 0.72 m high
            var cam = new Pose(new Vector3(0.3f, 1.45f, -0.2f), Quaternion.Euler(40, 30, 0));
            foreach (var p in new[] { new Vector3(0.55f, 0.72f, 0.35f), new Vector3(0.9f, 0.72f, 0.5f), new Vector3(0.4f, 0.72f, 0.9f) })
            {
                var px = Project(cam, p);
                Assert.That(px.x, Is.InRange(0, K.width)); Assert.That(px.y, Is.InRange(0, K.height));
                var box = Box(px - new Vector2(30, 20), px + new Vector2(30, 20));
                Assert.IsTrue(WorldMath.BoxOnTable(box, K, cam, 0.72f, out var hit, out _, out _));
                Assert.AreEqual(0.72f, hit.y, 1e-4f);
                AssertVec(p, hit, 0.005f, "table point");      // the box is whole 0-1000 units: about 1 px of rounding
            }
        }

        [Test]
        public void BoxOnTable_HigherInThePicture_IsFartherOnTheTable()
        {
            var cam = new Pose(new Vector3(0, 1.5f, 0), Quaternion.Euler(45, 0, 0));
            Assert.IsTrue(WorldMath.BoxOnTable(new[] { 150, 450, 250, 550 }, K, cam, 0.7f, out var top, out _, out _));
            Assert.IsTrue(WorldMath.BoxOnTable(new[] { 750, 450, 850, 550 }, K, cam, 0.7f, out var bottom, out _, out _));
            Assert.That(top.z, Is.GreaterThan(bottom.z + 0.3f), "y = 0 is the TOP of the picture");
            Assert.IsTrue(WorldMath.BoxOnTable(new[] { 450, 750, 550, 850 }, K, cam, 0.7f, out var right, out _, out _));
            Assert.That(right.x, Is.GreaterThan(0.1f));
        }

        [Test]
        public void BoxOnTable_AboveTheHorizon_IsNotFound()
        {
            Assert.IsFalse(WorldMath.BoxOnTable(new[] { 0, 400, 100, 600 }, K, new Pose(new Vector3(0, 1.5f, 0), Quaternion.identity), 0.7f, out _, out _, out _));
            Assert.IsFalse(WorldMath.BoxOnTable(new[] { 400, 400, 600, 600 }, default, Pose.identity, 0f, out _, out _, out _), "invalid intrinsics");
        }

        [Test]
        public void BoxOnTable_SizeAndLongAxis_OfABoardSeenFromAbove()
        {
            // camera 1 m above the table looking straight down, top of the picture towards world +Z
            var cam = new Pose(new Vector3(0, 1, 0), Quaternion.Euler(90, 0, 0));
            var a = Project(cam, new Vector3(-0.19f, 0, 0.065f));      // far-left corner: top-left in the picture
            var b = Project(cam, new Vector3(0.19f, 0, -0.065f));
            Assert.That(a.x, Is.LessThan(b.x)); Assert.That(a.y, Is.LessThan(b.y));
            Assert.IsTrue(WorldMath.BoxOnTable(Box(a, b), K, cam, 0f, out var c, out var size, out var across));
            AssertVec(Vector3.zero, c, 0.003f);
            Assert.AreEqual(0.38f, size.x, 0.004f);
            Assert.AreEqual(0.13f, size.y, 0.004f);
            AssertVec(Vector3.right, across, 0.01f);
            var axis = WorldMath.LongAxis(size, across);
            Assert.IsTrue(axis.HasValue);
            AssertVec(Vector3.right, axis.Value, 0.01f);
        }

        [Test]
        public void LongAxis_OnlyWhenClearlyElongated()
        {
            AssertVec(Vector3.right, WorldMath.LongAxis(new Vector2(0.38f, 0.13f), Vector3.right).Value);
            Assert.That(Mathf.Abs(Vector3.Dot(WorldMath.LongAxis(new Vector2(0.13f, 0.38f), Vector3.right).Value, Vector3.forward)), Is.EqualTo(1f).Within(1e-4f));
            Assert.IsFalse(WorldMath.LongAxis(new Vector2(0.30f, 0.25f), Vector3.right).HasValue);
            Assert.IsFalse(WorldMath.LongAxis(new Vector2(0.30f, 0f), Vector3.right).HasValue);
        }

        [Test]
        public void BoxToUvRect_FlipsYUp_AndWorldExtents_FollowTheRectangle()
        {
            var r = WorldMath.BoxToUvRect(new[] { 100, 200, 300, 600 }).Value;      // near the TOP of the picture
            Assert.AreEqual(0.2f, r.xMin, 1e-5f); Assert.AreEqual(0.6f, r.xMax, 1e-5f);
            Assert.AreEqual(0.7f, r.yMin, 1e-5f); Assert.AreEqual(0.9f, r.yMax, 1e-5f);
            Assert.IsFalse(WorldMath.BoxToUvRect(new[] { 1, 2 }).HasValue);
            var e = WorldMath.WorldExtents(new Vector2(0.38f, 0.13f), Vector3.right);
            Assert.AreEqual(0.38f, e.x, 1e-5f); Assert.AreEqual(0.13f, e.y, 1e-5f);
            e = WorldMath.WorldExtents(new Vector2(0.38f, 0.13f), Vector3.forward);    // seen from the side: long along world Z
            Assert.AreEqual(0.13f, e.x, 1e-5f); Assert.AreEqual(0.38f, e.y, 1e-5f);
        }

        // ---- placement: head fallback and tables ----------------------------------------------------------------

        [Test]
        public void HeadFallback_Is55cmAhead_35cmBelow_Upright_FrontToTheUser()
        {
            var head = new Pose(new Vector3(1, 1.6f, 2), Quaternion.Euler(30, 90, 0));          // facing +X, looking 30 degrees down
            var p = WorldMath.HeadFallback(head);
            AssertVec(new Vector3(1.55f, 1.25f, 2), p.position);
            AssertVec(Vector3.right, p.rotation * Vector3.forward);                            // +Z away from the user: -Z side faces them
            AssertVec(Vector3.up, p.rotation * Vector3.up);
        }

        [Test]
        public void HeadFallback_LookingStraightDown_StillKnowsWhereAheadIs()
        {
            var head = new Pose(new Vector3(0, 1.6f, 0), Quaternion.Euler(90, 40, 0));
            var want = Quaternion.Euler(0, 40, 0) * Vector3.forward;
            AssertVec(want, WorldMath.HorizontalForward(head), 1e-3f);
            AssertVec(new Vector3(0, 1.25f, 0) + want * 0.55f, WorldMath.HeadFallback(head).position, 1e-3f);
            // tilted head (roll) does not tilt the anchor
            var rolled = WorldMath.HeadFallback(new Pose(Vector3.up, Quaternion.Euler(10, 0, 35)));
            AssertVec(Vector3.up, rolled.rotation * Vector3.up);
        }

        static TableSurface Desk(Vector3 centre, float halfX = 0.6f, float halfZ = 0.3f)
            => new TableSurface { Centre = centre, AxisA = Vector3.right, AxisB = Vector3.forward, HalfA = halfX, HalfB = halfZ };

        [Test]
        public void TableSurface_FromAnchor_AnyAxisConvention()
        {
            // a volume whose local Y is up, origin on the top face
            Assert.IsTrue(TableSurface.TryFromLocalBounds(new Pose(new Vector3(2, 0.75f, 1), Quaternion.identity),
                new Bounds(new Vector3(0, -0.375f, 0), new Vector3(1.2f, 0.75f, 0.6f)), out var t));
            AssertVec(new Vector3(2, 0.75f, 1), t.Centre);
            Assert.IsTrue(t.Contains(new Vector3(2.55f, 0, 1.25f)));
            Assert.IsFalse(t.Contains(new Vector3(2.0f, 0, 1.35f)));
            // a plane whose local Z is the normal, pointing up, turned 30 degrees on the floor
            var rot = Quaternion.Euler(0, 30, 0) * Quaternion.LookRotation(Vector3.up, Vector3.forward);
            Assert.IsTrue(TableSurface.TryFromLocalBounds(new Pose(new Vector3(0, 0.7f, 0), rot), new Bounds(Vector3.zero, new Vector3(1.2f, 0.6f, 0)), out var p));
            Assert.AreEqual(0.7f, p.TopY, 1e-4f);
            var along = Quaternion.Euler(0, 30, 0) * Vector3.right;
            Assert.IsTrue(p.Contains(along * 0.55f));
            Assert.IsFalse(p.Contains(Quaternion.Euler(0, 30, 0) * Vector3.forward * 0.35f));
            // a volume with local Z down (top face at the origin, body below): top is still the highest face
            Assert.IsTrue(TableSurface.TryFromLocalBounds(new Pose(new Vector3(0, 0.8f, 0), Quaternion.LookRotation(Vector3.down, Vector3.forward)),
                new Bounds(new Vector3(0, 0, 0.4f), new Vector3(1.0f, 0.5f, 0.8f)), out var d));
            Assert.AreEqual(0.8f, d.TopY, 1e-4f);
            // a wall, and a board leaning 45 degrees, are not tables
            Assert.IsFalse(TableSurface.TryFromLocalBounds(new Pose(Vector3.up, Quaternion.identity), new Bounds(Vector3.zero, new Vector3(2, 2.5f, 0)), out _));
            Assert.IsFalse(TableSurface.TryFromLocalBounds(new Pose(Vector3.up, Quaternion.Euler(45, 0, 0)), new Bounds(Vector3.zero, new Vector3(1, 0.1f, 1)), out _));
        }

        [Test]
        public void TryPlaceOnTables_PicksTheTableInFront_AtTableHeight()
        {
            var tables = new List<TableSurface> { Desk(new Vector3(0, 0.74f, -2f)), Desk(new Vector3(0, 0.74f, 0.6f)), Desk(new Vector3(3, 0.74f, 0.6f)) };
            var head = new Pose(new Vector3(0, 1.5f, 0), Quaternion.identity);
            Assert.IsTrue(WorldMath.TryPlaceOnTables(tables, head, out var place, out int i));
            Assert.AreEqual(1, i);
            AssertVec(new Vector3(0, 0.74f, 0.55f), place.position);
            AssertVec(Vector3.forward, place.rotation * Vector3.forward);
            AssertVec(Vector3.up, place.rotation * Vector3.up);
        }

        [Test]
        public void TryPlaceOnTables_UsesTheGaze_AndStaysInsideTheEdges()
        {
            var desk = Desk(new Vector3(0, 0.74f, 0.6f));
            var tables = new List<TableSurface> { desk };
            var eye = new Vector3(0, 1.5f, 0);
            var spot = new Vector3(0.3f, 0.74f, 0.7f);
            Assert.IsTrue(WorldMath.TryPlaceOnTables(tables, new Pose(eye, Quaternion.LookRotation(spot - eye)), out var place, out _));
            AssertVec(spot, place.position, 1e-3f);
            // facing the user: +Z points from the head to the spot, flat
            AssertVec(Vector3.ProjectOnPlane(spot - eye, Vector3.up).normalized, place.rotation * Vector3.forward, 1e-3f);
            // looking at the near edge: pulled 12 cm inside
            var edge = new Vector3(0, 0.74f, 0.31f);
            Assert.IsTrue(WorldMath.TryPlaceOnTables(tables, new Pose(eye, Quaternion.LookRotation(edge - eye)), out place, out _));
            Assert.AreEqual(0.3f + WorldMath.EdgeMarginM, place.position.z, 1e-3f);
            // standing left of the desk looking past it: the nearest usable point of the desk, not a point in the air
            Assert.IsTrue(WorldMath.TryPlaceOnTables(tables, new Pose(new Vector3(-1.0f, 1.5f, 0.6f), Quaternion.identity), out place, out _));
            Assert.IsTrue(desk.Contains(place.position, WorldMath.EdgeMarginM - 1e-3f));
            Assert.AreEqual(0.74f, place.position.y, 1e-4f);
        }

        [Test]
        public void TryPlaceOnTables_NoUsableTable_FallsBack()
        {
            var head = new Pose(new Vector3(0, 1.5f, 0), Quaternion.identity);
            Assert.IsFalse(WorldMath.TryPlaceOnTables(new List<TableSurface>(), head, out _, out _));
            Assert.IsFalse(WorldMath.TryPlaceOnTables(null, head, out _, out _));
            Assert.IsFalse(WorldMath.TryPlaceOnTables(new List<TableSurface> { Desk(new Vector3(0, 1.6f, 0.6f)) }, head, out _, out _), "a shelf above the eyes");
            Assert.IsFalse(WorldMath.TryPlaceOnTables(new List<TableSurface> { Desk(new Vector3(0, 0.74f, 5f)) }, head, out _, out _), "a table across the room");
        }

        // ---- when to place -------------------------------------------------------------------------------------

        [Test]
        public void Policy_ProvisionalUntilStarted_ThenOnce_ThenStaysPut()
        {
            var p = new PlacementPolicy();
            var head = new Pose(new Vector3(0, 1.5f, 0), Quaternion.identity);
            var anchor = new Vector3(0, 1.15f, 0.55f);
            Assert.AreEqual(PlaceDecision.Provisional, p.Step(false, false, head, anchor, 0.014f));
            Assert.AreEqual(PlaceDecision.Provisional, p.Step(false, false, head, anchor, 0.014f));
            Assert.AreEqual(PlaceDecision.Place, p.Step(false, true, head, anchor, 0.014f));
            Assert.AreEqual("first", p.Reason);
            for (int i = 0; i < 2000; i++) Assert.AreEqual(PlaceDecision.None, p.Step(false, true, head, anchor, 0.014f), "must not jump once the user has started");
        }

        [Test]
        public void Policy_ReplacesWhenLeftBehind_OrOutOfView_ForSeconds()
        {
            var p = new PlacementPolicy();
            var anchor = new Vector3(0, 1.15f, 0.55f);
            var near = new Pose(new Vector3(0, 1.5f, 0), Quaternion.identity);
            const float dt = 0.125f;                                                            // exact in binary: 24 steps = 3 s
            p.Step(false, true, near, anchor, dt);
            var far = new Pose(new Vector3(0, 1.5f, -1.2f), Quaternion.identity);               // 1.75 m away, still in view
            for (int i = 0; i < 23; i++) Assert.AreEqual(PlaceDecision.None, p.Step(false, true, far, anchor, dt));
            Assert.AreEqual(PlaceDecision.None, p.Step(false, true, near, anchor, dt), "coming back resets the clock");
            for (int i = 0; i < 23; i++) Assert.AreEqual(PlaceDecision.None, p.Step(false, true, far, anchor, dt));
            Assert.AreEqual(PlaceDecision.Replace, p.Step(false, true, far, anchor, dt));
            Assert.AreEqual("far", p.Reason);

            var away = new Pose(new Vector3(0, 1.5f, 0), Quaternion.Euler(0, 120, 0));          // near, looking elsewhere
            for (int i = 0; i < 47; i++) Assert.AreEqual(PlaceDecision.None, p.Step(false, true, away, anchor, dt));
            Assert.AreEqual(PlaceDecision.Replace, p.Step(false, true, away, anchor, dt));
            Assert.AreEqual("out_of_view", p.Reason);
        }

        [Test]
        public void Policy_NeverFightsATrack_AndOnlyARequestPlacesAfterOne()
        {
            var p = new PlacementPolicy();
            var anchor = new Vector3(0, 1.15f, 0.55f);
            var far = new Pose(new Vector3(0, 1.5f, -3f), Quaternion.Euler(0, 180, 0));
            Assert.AreEqual(PlaceDecision.None, p.Step(true, true, far, anchor, 0.1f), "tracked before any placement: no placement");
            p.Request();
            Assert.AreEqual(PlaceDecision.None, p.Step(true, true, far, anchor, 0.1f), "a request during a live track is dropped");
            for (int i = 0; i < 200; i++) Assert.AreEqual(PlaceDecision.None, p.Step(false, true, far, anchor, 0.1f), "track lost: the anchor stays where the track left it");
            p.Request();
            Assert.AreEqual(PlaceDecision.Replace, p.Step(false, true, far, anchor, 0.1f));
            Assert.AreEqual("request", p.Reason);
        }

        [Test]
        public void Policy_Pinned_OnTheRealObject_IsNotReplacedAutomatically()
        {
            var p = new PlacementPolicy();
            var anchor = new Vector3(0, 0.74f, 0.55f);
            var far = new Pose(new Vector3(0, 1.5f, -3f), Quaternion.Euler(0, 180, 0));
            p.Step(false, true, new Pose(new Vector3(0, 1.5f, 0), Quaternion.identity), anchor, 0.1f);
            p.Pin();
            for (int i = 0; i < 200; i++) Assert.AreEqual(PlaceDecision.None, p.Step(false, true, far, anchor, 0.1f));
            p.Request();
            Assert.AreEqual(PlaceDecision.Replace, p.Step(false, true, far, anchor, 0.1f));
            Assert.IsFalse(p.Pinned);
        }

        // ---- no jitter -------------------------------------------------------------------------------------------

        [Test]
        public void PoseGate_FirstAtOnce_SmallChangesNever_BigOnesOnlyWhenTheyPersist()
        {
            var g = new PoseGate();
            var at = new Pose(new Vector3(0, 0.74f, 0.5f), Quaternion.identity);
            Assert.IsTrue(g.Offer(at, at, 0f), "the first pose is taken");
            var rng = new System.Random(7);
            for (int i = 0; i < 300; i++)
            {
                var noisy = new Pose(at.position + new Vector3((float)rng.NextDouble() - 0.5f, 0, (float)rng.NextDouble() - 0.5f) * 0.12f,
                    Quaternion.Euler(0, ((float)rng.NextDouble() - 0.5f) * 20f, 0));
                Assert.IsFalse(g.Offer(at, noisy, 1f + i * 0.1f), "within 10 cm / 20 degrees: the ghost does not move");
            }
            var moved = new Pose(at.position + new Vector3(0.25f, 0, 0), Quaternion.identity);
            Assert.IsFalse(g.Offer(at, moved, 100f), "one disagreeing answer is not enough");
            Assert.IsFalse(g.Offer(at, moved, 102f));
            Assert.IsTrue(g.Offer(at, new Pose(moved.position + new Vector3(0.02f, 0, 0), Quaternion.identity), 103.1f), "same answer for 3 s: move");
        }

        [Test]
        public void PoseGate_ScatteredAnswersNeverMove_ForcedOnesAlwaysDo_YawCounts()
        {
            var g = new PoseGate();
            var at = new Pose(Vector3.zero, Quaternion.identity);
            g.Offer(at, at, 0f);
            for (int i = 0; i < 100; i++)
            {
                var jump = new Pose(new Vector3(i % 2 == 0 ? 0.4f : -0.4f, 0, 0), Quaternion.identity);
                Assert.IsFalse(g.Offer(at, jump, 1f + i), "answers that disagree with each other never move the ghost");
            }
            Assert.IsTrue(g.Offer(at, new Pose(new Vector3(0.4f, 0, 0), Quaternion.identity), 200f, force: true));
            var turned = new Pose(Vector3.zero, Quaternion.Euler(0, 60, 0));
            Assert.IsFalse(g.Offer(at, turned, 300f));
            Assert.IsTrue(g.Offer(at, turned, 303.5f), "a persistent 60 degree turn is a disagreement too");
            g.Reset();
            Assert.IsTrue(g.Offer(at, at, 400f));
        }

        // ---- anchor onto the part the camera found -----------------------------------------------------------------

        [Test]
        public void AlignAnchor_SlidesThePartOntoTheTarget_KeepsHeightAndYaw()
        {
            var anchor = new Pose(new Vector3(1, 0.74f, 1), Quaternion.Euler(0, 25, 0));
            var local = new Vector3(0.1f, 0.05f, 0.2f);                                        // the part's centre in the assembly frame
            var centre = anchor.position + anchor.rotation * local;
            var target = new Vector3(2, 0.70f, 3);
            var p = WorldMath.AlignAnchor(anchor, centre, anchor.rotation * Vector3.right, target, null);
            var now = p.position + p.rotation * local;
            Assert.AreEqual(target.x, now.x, 1e-4f); Assert.AreEqual(target.z, now.z, 1e-4f);
            Assert.AreEqual(centre.y, now.y, 1e-4f, "height is not the camera's business");
            Assert.That(Quaternion.Angle(anchor.rotation, p.rotation), Is.LessThan(1e-2f));
        }

        [Test]
        public void AlignAnchor_TurnsThePartAlongTheBox_BySmallerTurn_AboutThePart()
        {
            var anchor = new Pose(new Vector3(1, 0.74f, 1), Quaternion.identity);
            var local = new Vector3(0.1f, 0.05f, 0.2f);
            var centre = anchor.position + local;
            var target = new Vector3(2, 0.74f, 3);
            foreach (float deg in new[] { 10f, 80f, 100f, 170f, -30f })
            {
                var axis = Quaternion.Euler(0, deg, 0) * Vector3.right;
                var p = WorldMath.AlignAnchor(anchor, centre, Vector3.right, target, axis);
                var partAxis = p.rotation * Vector3.right;
                Assert.That(Mathf.Abs(Vector3.Dot(partAxis, axis)), Is.EqualTo(1f).Within(1e-3f), $"{deg}: long edges line up");
                Assert.That(Quaternion.Angle(anchor.rotation, p.rotation), Is.LessThanOrEqualTo(90.01f), $"{deg}: the smaller turn");
                var now = p.position + p.rotation * local;
                Assert.AreEqual(target.x, now.x, 1e-3f); Assert.AreEqual(target.z, now.z, 1e-3f);
                AssertVec(Vector3.up, p.rotation * Vector3.up, 1e-3f, "stays upright");
            }
            // a part standing on end has no long edge on the table: no turn
            var up = WorldMath.AlignAnchor(anchor, centre, Vector3.up, target, Vector3.forward);
            Assert.That(Quaternion.Angle(anchor.rotation, up.rotation), Is.LessThan(1e-2f));
        }

        [Test]
        public void FootprintHull_OfABox_IsItsRectangleOnTheTable()
        {
            var rot = Quaternion.Euler(0, 30, 0);
            var pts = new List<Vector3>();
            for (int i = 0; i < 8; i++)
                pts.Add(new Vector3(1, 0.8f, 2) + rot * new Vector3((i & 1) == 0 ? -0.19f : 0.19f, (i & 2) == 0 ? 0 : 0.016f, (i & 4) == 0 ? -0.065f : 0.065f));
            var hull = WorldMath.FootprintHull(pts, 0.743f);
            Assert.AreEqual(4, hull.Count);
            float area = 0;
            for (int i = 0; i < hull.Count; i++)
            {
                Assert.AreEqual(0.743f, hull[i].y, 1e-6f);
                var a = hull[i]; var b = hull[(i + 1) % hull.Count];
                area += a.x * b.z - b.x * a.z;
            }
            Assert.AreEqual(0.38f * 0.13f, area / 2, 1e-4f, "counter-clockwise seen from above, the board's own area");
            Assert.AreEqual(0, WorldMath.FootprintHull(new List<Vector3>(), 0).Count);
        }

        // ---- words and the base part ---------------------------------------------------------------------------------

        [Test]
        public void CoachLine_BothLanguages()
        {
            Assert.AreEqual("Place the drawer front inside the blue outline.", WorldText.PlaceInOutline("en"));
            StringAssert.Contains("μπλε περίγραμμα", WorldText.PlaceInOutline("el"));
            StringAssert.Contains("πρόσοψη", WorldText.PlaceInOutline("el"));
            Assert.AreEqual("Place the left side panel inside the blue outline.", WorldText.PlaceInOutline("en", "Left side panel"));
            StringAssert.Contains("Αριστερό πλαϊνό", WorldText.PlaceInOutline("el", "Αριστερό πλαϊνό"));
            Assert.IsNotEmpty(WorldText.PlaceInOutlinePill("el"));
            Assert.IsNotEmpty(WorldText.PlaceInOutlinePill("en"));
        }

        [Test]
        public void BasePart_IsTheLargestBoardNoActionPlaces()
        {
            var g = new AssemblyGuide();
            g.Nodes["hw_118331_1"] = new PartNode { Node = "hw_118331_1", PartId = "118331", IsHardware = true, FinalBounds = new Bounds(Vector3.zero, Vector3.one) };
            g.Nodes["part_side_1"] = new PartNode { Node = "part_side_1", PartId = "side", PlacedInStep = 2, FinalBounds = new Bounds(Vector3.zero, new Vector3(0.4f, 0.13f, 0.016f)) };
            g.Nodes["part_front_1"] = new PartNode { Node = "part_front_1", PartId = "front", FinalBounds = new Bounds(Vector3.zero, new Vector3(0.38f, 0.13f, 0.016f)) };
            g.Nodes["part_cleat_1"] = new PartNode { Node = "part_cleat_1", PartId = "cleat", FinalBounds = new Bounds(Vector3.zero, new Vector3(0.1f, 0.02f, 0.02f)) };
            Assert.AreEqual("front", WorldModule.BasePart(g).PartId);
            g.Nodes.Remove("part_front_1"); g.Nodes.Remove("part_cleat_1");
            Assert.AreEqual("side", WorldModule.BasePart(g).PartId, "every board is placed by an action: the first one placed");
            Assert.IsNull(WorldModule.BasePart(null));
        }
    }
}

using UnityEngine;

namespace AG.Guide
{
    /// <summary>
    /// Blender (right-handed, Z up) to Unity (left-handed, Y up) for the manifest's vectors.
    ///
    /// Chain: Blender (x, y, z) -> glTF (x, z, -y)  [the pipeline's export, see client/src/xr/jobAssembly.ts]
    ///        glTF (x, y, z)    -> Unity (-x, y, z) [glTFast negates X on import]
    /// so     Blender (x, y, z) -> Unity (-x, z, -y).
    ///
    /// All results are in the ASSEMBLY FRAME: the local frame of the GLB root node <c>assembly</c> as glTFast
    /// instantiates it (metres). VERIFIED 15 Nov 2026 by the EditMode test
    /// <c>AG.Tests.GuideLoadTests</c>: <c>BlenderPointToUnity(action.target_m)</c> equals the node's final local
    /// position at the end of the walkthrough clip within 2 mm for every hardware action of the MALM job.
    /// </summary>
    public static class FrameConvert
    {
        /// <summary>Blender -> Unity basis change. det = -1 (handedness flip).</summary>
        static readonly Matrix4x4 M = new Matrix4x4(
            new Vector4(-1, 0, 0, 0),   // column 0: image of Blender X
            new Vector4(0, 0, -1, 0),   // column 1: image of Blender Y
            new Vector4(0, 1, 0, 0),    // column 2: image of Blender Z
            new Vector4(0, 0, 0, 1));

        /// <summary>Point or offset in metres, Blender Z-up -> Unity assembly frame.</summary>
        public static Vector3 BlenderPointToUnity(float[] v) =>
            v == null || v.Length < 3 ? Vector3.zero : new Vector3(-v[0], v[2], -v[1]);

        /// <summary>glTF (right-handed, Y up) position -> Unity, the way glTFast imports it (negate X).</summary>
        public static Vector3 GltfPointToUnity(float x, float y, float z) => new Vector3(-x, y, z);

        /// <summary>glTF rotation quaternion (x, y, z, w) -> Unity, the way glTFast imports it.</summary>
        public static Quaternion GltfRotationToUnity(float x, float y, float z, float w) => new Quaternion(x, -y, -z, w);

        /// <summary>Direction (normalised), Blender Z-up -> Unity assembly frame.</summary>
        public static Vector3 BlenderDirToUnity(float[] v) => BlenderPointToUnity(v).normalized;

        /// <summary>
        /// Blender XYZ Euler in degrees (Blender's default order: R = Rz * Ry * Rx) -> Unity rotation in the
        /// assembly frame. Conjugates the rotation by the basis change: R_u = M R_b M^-1.
        /// </summary>
        public static Quaternion BlenderEulerDegToUnity(float[] deg)
        {
            if (deg == null || deg.Length < 3) return Quaternion.identity;
            float rx = deg[0] * Mathf.Deg2Rad, ry = deg[1] * Mathf.Deg2Rad, rz = deg[2] * Mathf.Deg2Rad;
            Matrix4x4 Rx = Rot(0, rx), Ry = Rot(1, ry), Rz = Rot(2, rz);
            Matrix4x4 rb = Rz * Ry * Rx;                       // right-handed, Blender frame
            Matrix4x4 ru = M * rb * M.inverse;                 // proper rotation in Unity frame
            return ru.rotation;
        }

        // right-handed rotation matrix about axis 0=X, 1=Y, 2=Z (the math is the same; the frame is Blender's)
        static Matrix4x4 Rot(int axis, float a)
        {
            float c = Mathf.Cos(a), s = Mathf.Sin(a);
            var m = Matrix4x4.identity;
            switch (axis)
            {
                case 0: m.m11 = c; m.m12 = -s; m.m21 = s; m.m22 = c; break;
                case 1: m.m00 = c; m.m02 = s; m.m20 = -s; m.m22 = c; break;
                default: m.m00 = c; m.m01 = -s; m.m10 = s; m.m11 = c; break;
            }
            return m;
        }
    }
}

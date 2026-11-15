namespace AG.World
{
    /// <summary>
    /// The world layer's user-facing lines (el + en), for the coach window, the status pill and the voice. The UI
    /// owns where they are shown; this only owns the words.
    /// </summary>
    public static class WorldText
    {
        /// <summary>
        /// Coach line while nothing tracks the real object: "Place the drawer front inside the blue outline."
        /// <paramref name="partName"/>: the base part's display name in <paramref name="lang"/> (manifest names are
        /// nominative, so the Greek line names the part after a colon instead of declining it); null or empty gives
        /// the drawer-front sentence.
        /// </summary>
        public static string PlaceInOutline(string lang, string partName = null)
        {
            bool el = lang == "el";
            if (string.IsNullOrWhiteSpace(partName))
                return el ? "Βάλε την πρόσοψη του συρταριού μέσα στο μπλε περίγραμμα." : "Place the drawer front inside the blue outline.";
            partName = partName.Trim();
            return el ? "Βάλε μέσα στο μπλε περίγραμμα: " + partName + "." : "Place the " + partName.ToLowerInvariant() + " inside the blue outline.";
        }

        /// <summary>Short form for the status pill.</summary>
        public static string PlaceInOutlinePill(string lang)
            => lang == "el" ? "Βάλε το κομμάτι στο μπλε περίγραμμα" : "Put the part in the blue outline";

        /// <summary>Said once when the camera found the part and the guide moved onto it.</summary>
        public static string FoundIt(string lang) => lang == "el" ? "Το βρήκα. Ο οδηγός είναι πάνω του." : "Found it. The guide is on it.";

        /// <summary>Label of a "put the guide here again" control.</summary>
        public static string Realign(string lang) => lang == "el" ? "Ευθυγράμμιση ξανά" : "Align again";

        /// <summary>Prefix of the pick-up pill (shown uppercase).</summary>
        public static string PickUp(string lang) => lang == "el" ? "Πάρε" : "Pick up";
    }
}

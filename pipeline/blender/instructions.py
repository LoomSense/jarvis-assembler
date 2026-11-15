"""Instruction text for the manifest's actions, in English and Greek. Plain Python: Blender imports it, tests too.

The verb comes from what the piece is (the plan's hardware `kind`, and `press` on the add): a screw is screwed in,
a dowel pushed into its hole, a cam lock dropped in and turned until it locks, a pin pressed in, a nail tapped in.
Greek needs the noun's gender for the article and the pronoun ("μια βίδα ... βίδωσέ την", "έναν πείρο ... βάλε τον",
"ένα καρφάκι ... βάλε το"), so the head noun of the plan's Greek name is looked up in a small table. A name we cannot
place falls back to "το εξάρτημα <code>", which is always correct. Codes and counts are only ever copied.
"""
import unicodedata

ARTICLES = ("τους ", "τις ", "την ", "τον ", "τη ", "το ", "τα ", "οι ", "η ", "ο ", "έναν ", "ένα ", "ένας ", "μια ", "μία ")
ARTICLE_GENDER = {"τον": "m", "ο": "m", "έναν": "m", "ένας": "m", "την": "f", "τη": "f", "η": "f", "μια": "f", "μία": "f",
                  "το": "n", "ένα": "n"}
INDEF = {"m": "έναν", "f": "μια", "n": "ένα"}
DEF = {"m": "τον", "f": "την", "n": "το"}
PRON = DEF
POSS = {"m": "του", "f": "της", "n": "του"}

# head noun (accusative singular) -> (gender, nominative singular); nominative forms are looked up too
_NOUNS = [
    ("βίδα", "f", "βίδα"), ("καβίλια", "f", "καβίλια"), ("πρόκα", "f", "πρόκα"), ("κλειδαριά", "f", "κλειδαριά"),
    ("τάπα", "f", "τάπα"), ("ροδέλα", "f", "ροδέλα"), ("γωνία", "f", "γωνία"), ("λαβή", "f", "λαβή"),
    ("ρόδα", "f", "ρόδα"), ("βάση", "f", "βάση"), ("πλάκα", "f", "πλάκα"),
    ("καβιλάκι", "n", "καβιλάκι"), ("καρφί", "n", "καρφί"), ("καρφάκι", "n", "καρφάκι"), ("έκκεντρο", "n", "έκκεντρο"),
    ("ούπα", "n", "ούπα"), ("παξιμάδι", "n", "παξιμάδι"), ("μπουλόνι", "n", "μπουλόνι"), ("ταπάκι", "n", "ταπάκι"),
    ("καπάκι", "n", "καπάκι"), ("δαχτυλίδι", "n", "δαχτυλίδι"), ("δακτυλίδι", "n", "δακτυλίδι"),
    ("στήριγμα", "n", "στήριγμα"), ("κλιπ", "n", "κλιπ"), ("ελατήριο", "n", "ελατήριο"), ("πόμολο", "n", "πόμολο"),
    ("χερούλι", "n", "χερούλι"), ("ροδάκι", "n", "ροδάκι"), ("ποδαράκι", "n", "ποδαράκι"), ("πόδι", "n", "πόδι"),
    ("βύσμα", "n", "βύσμα"), ("κλειδί", "n", "κλειδί"), ("εξάρτημα", "n", "εξάρτημα"), ("τεμάχιο", "n", "τεμάχιο"),
    ("πείρο", "m", "πείρος"), ("ρεγουλατόρο", "m", "ρεγουλατόρος"), ("δακτύλιο", "m", "δακτύλιος"),
    ("δαχτύλιο", "m", "δαχτύλιος"), ("μεντεσέ", "m", "μεντεσές"), ("οδηγό", "m", "οδηγός"), ("τροχό", "m", "τροχός"),
    ("σύνδεσμο", "m", "σύνδεσμος"), ("συνδετήρα", "m", "συνδετήρας"), ("αποστάτη", "m", "αποστάτης"),
    ("δίσκο", "m", "δίσκος"), ("γάντζο", "m", "γάντζος"), ("βραχίονα", "m", "βραχίονας"),
]


def _plain(word):
    """Lower case without accents, so "Έκκεντρο" and "εκκεντρο" match."""
    return "".join(c for c in unicodedata.normalize("NFD", word.lower()) if unicodedata.category(c) != "Mn")


NOUNS = {}
for _acc, _g, _nom in _NOUNS:
    NOUNS[_plain(_acc)] = (_g, _nom, _acc)
    NOUNS.setdefault(_plain(_nom), (_g, _nom, _acc))

# how each kind of piece goes in, and what the counter calls it ("Dowel 1 of 2")
HOW = {"screw": "screw", "bolt": "screw", "leveler": "screw", "leveller": "screw", "foot": "screw",
       "dowel": "push", "cam": "cam", "pin": "press_in", "nail": "tap", "tack": "tap", "staple": "tap",
       "cover": "press_on", "cap": "press_on", "sleeve": "press_on", "plug": "press_in", "clip": "press_on"}
COUNTER_EN = {"screw": "Screw", "bolt": "Bolt", "dowel": "Dowel", "cam": "Cam lock", "pin": "Pin", "nail": "Nail",
              "leveler": "Leveler", "leveller": "Leveller", "sleeve": "Sleeve", "cover": "Cover", "cap": "Cap",
              "plug": "Plug", "clip": "Clip", "washer": "Washer", "nut": "Nut", "bracket": "Bracket",
              "hinge": "Hinge", "tack": "Tack", "staple": "Staple", "foot": "Foot"}
COUNTER_EL = {"screw": "Βίδα", "bolt": "Βίδα", "dowel": "Καβίλια", "cam": "Έκκεντρο", "pin": "Πείρος", "nail": "Καρφί"}
VERB_EN = {"screw": "screw it in here", "screw_hand": "screw it in here by hand",
           "push": "push it into the hole here",
           "cam": "drop it into the round hole here, then turn it clockwise until it locks",
           "press_in": "press it in here", "tap": "tap it in here with a hammer",
           "press_on": "press it into place here", "put": "put it in here"}
VERB_EL = {"screw": "βίδωσέ {p} εδώ", "screw_hand": "βίδωσέ {p} εδώ με το χέρι",
           "push": "σπρώξε {p} μέσα στην τρύπα εδώ",
           "cam": "βάλε {p} στη στρογγυλή τρύπα εδώ, μετά γύρισέ {p} δεξιόστροφα μέχρι να κλειδώσει",
           "press_in": "πίεσέ {p} μέσα εδώ", "tap": "κάρφωσέ {p} εδώ με ένα σφυρί",
           "press_on": "πίεσέ {p} στη θέση {s} εδώ", "put": "βάλε {p} εδώ"}


def strip_article(el):
    """The plan's Greek name without a leading article, and the gender that article gives (or None)."""
    el = (el or "").strip()
    for a in ARTICLES:
        if el.lower().startswith(a):
            return el[len(a):].strip(), ARTICLE_GENDER.get(a.strip())
    return el, None


def names(d):
    """{"en", "el"} display names: `short` in English, and the plan's Greek `el` without its leading article."""
    el = strip_article(d.get("el"))[0]
    return {"en": d.get("short", d["name"]), "el": el or d.get("short", d["name"])}


def greek_noun(el):
    """(phrase in the accusative, gender, nominative head noun) for a Greek name, or None if its noun is unknown.

    The head noun is the first known word ("βίδα έκκεντρου" -> βίδα, "τάπα έκκεντρου" -> τάπα). A masculine name
    written in the nominative ("μεταλλικός πείρος") is put in the accusative ("μεταλλικό πείρο").
    """
    phrase, art_gender = strip_article(el)
    words = phrase.split()
    for i, w in enumerate(words):
        hit = NOUNS.get(_plain(w.strip(",.;")))
        if not hit:
            continue
        g, nom, acc = hit
        if art_gender and art_gender != g:              # the plan's own article disagrees: trust neither
            return None
        if g == "m" and _plain(w) == _plain(nom) and nom != acc:
            words[i] = acc
            for j in range(i):                           # adjectives before it: -ος / -ός -> -ο / -ό
                if words[j].endswith(("ος", "ός")):
                    words[j] = words[j][:-1]
        return " ".join(words), g, nom
    return None


def how(d, press=False):
    """The verb key for putting this piece in: from `kind`, `press`, and a few words of its name."""
    kind = (d.get("kind") or "").lower()
    words = " ".join(str(d.get(k) or "") for k in ("name", "short")).lower()
    if kind == "cam" or ("cam" in words and "lock" in words):
        return "cam"
    if ("double" in words and "screw" in words) or "διπλή βίδα" in (d.get("el") or ""):
        return "screw_hand"
    if kind in ("dowel", "pin") and "cam" in words:     # a cam dowel / cam pin is threaded: it is screwed in
        return "screw"
    if press:
        return "press_in"
    return HOW.get(kind, "put")


def counter_en(d):
    kind = (d.get("kind") or "").lower()
    words = (d.get("short") or d.get("name") or "").lower()
    if kind == "bolt" and "cam" in words:
        return "Cam bolt"
    if kind == "cam" and "lock" not in words:
        return "Cam"
    return COUNTER_EN.get(kind, "Piece")


def _keeps_nu(word):
    """Greek final -ν rule: την stays before a vowel, κ π τ ξ ψ and μπ ντ γκ τσ τζ, and becomes τη elsewhere."""
    w = _plain(word)
    return not w[:1].isalpha() or w[:1] in "αεηιουωκπτξψ" or w[:2] in ("μπ", "ντ", "γκ", "τσ", "τζ")


def nu(text):
    """Apply the final -ν rule to every feminine article or pronoun την / τη in a Greek sentence."""
    words = text.split(" ")
    for i in range(len(words) - 1):
        if words[i] in ("την", "τη"):
            words[i] = "την" if _keeps_nu(words[i + 1]) else "τη"
    return " ".join(words)


def _an(word):
    return "an" if word[:1].lower() in "aeiou" else "a"


def fasten_text(d, i, n, press=False):
    """'Take a <name> (<code>) and <verb>. <Counter> i of n.' in English and Greek."""
    code = d["code"]
    name = (d.get("short") or d["name"]).lower()
    key = how(d, press)
    en = f"Take {_an(name)} {name} ({code}) and {VERB_EN[key]}. {counter_en(d)} {i} of {n}."
    kind = (d.get("kind") or "").lower()
    noun = greek_noun(d.get("el"))
    if noun:
        phrase, g, nom = noun
        counter = nom[:1].upper() + nom[1:]
        el = (f"Πάρε {INDEF[g]} {phrase} ({code}) και {VERB_EL[key].format(p=PRON[g], s=POSS[g])}. "
              f"{counter} {i} από {n}.")
    else:
        el = (f"Πάρε το εξάρτημα {code} και {VERB_EL[key].format(p='το', s='του')}. "
              f"{COUNTER_EL.get(kind, 'Τεμάχιο')} {i} από {n}.")
    return {"en": en, "el": nu(el)}


def tighten_text(d, i, n):
    code = d["code"]
    name = (d.get("short") or d["name"]).lower()
    kind = (d.get("kind") or "").lower()
    en = f"Tighten the {name} ({code}) firmly. {counter_en(d)} {i} of {n}."
    noun = greek_noun(d.get("el"))
    if noun:
        phrase, g, nom = noun
        el = f"Σφίξε καλά {DEF[g]} {phrase} ({code}). {nom[:1].upper() + nom[1:]} {i} από {n}."
    else:
        el = f"Σφίξε καλά το εξάρτημα {code}. {COUNTER_EL.get(kind, 'Τεμάχιο')} {i} από {n}."
    return {"en": en, "el": nu(el)}


def text_for(kind, d, i=0, n=0, first=False, press=False):
    """Instruction text for one action. `kind` is the action: place, press, join, fasten, tighten."""
    if kind == "tighten":
        return tighten_text(d, i, n)
    if kind == "fasten":
        return fasten_text(d, i, n, press)
    name, el = d.get("short", d["name"]).lower(), d.get("el", d["name"])
    if kind == "press":
        return {"en": f"Press the {name} in firmly until it clicks.",
                "el": f"Πίεσε {el} μέσα μέχρι να κουμπώσει."}
    if kind == "join":
        return {"en": f"Take the {name} you built and put it in place here.",
                "el": f"Πάρε {el} που έφτιαξες και βάλε {d.get('el_pron', 'το')} στη θέση που φαίνεται."}
    if first:
        return {"en": f"Lay the {name} flat in front of you.", "el": f"Ακούμπησε {el} μπροστά σου."}
    return {"en": f"Take the {name} and put it in place here.",
            "el": f"Πάρε {el} και βάλε {d.get('el_pron', 'το')} στη θέση που φαίνεται."}

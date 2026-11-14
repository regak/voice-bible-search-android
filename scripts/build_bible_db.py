"""
ONE-TIME offline data pipeline script — NOT shipped in the app.

Builds app/src/main/assets/bible.db from Biblica's "Toleo Wazi Neno:
Maandiko Matakatifu" (Open Kiswahili Contemporary Scriptures), CC BY-SA
4.0, via the Crosswire SWORD module distributed at
https://ebible.org/sword/zip/swhonmm2024eb.zip

Usage:
    pip install pysword
    curl -LO https://ebible.org/sword/zip/swhonmm2024eb.zip
    python3 -c "import zipfile; zipfile.ZipFile('swhonmm2024eb.zip').extractall('swordmod')"
    python3 scripts/build_bible_db.py swordmod app/src/main/assets/bible.db

Produces a SQLite file with:
- `verses` table: indexed (book_osis, book_name, book_order, chapter, verse, text)
  for exact book/chapter/verse lookups.
- `verses_fts` FTS4 virtual table over verse text for topical search.

Must retain the Biblica(R) CC BY-SA 4.0 attribution — see CreditsScreen
in the app and the full notice embedded in the SWORD module's
mods.d/swhonmm2024eb.conf (About= field).
"""
import sqlite3, os, re, sys
from pysword.modules import SwordModules

BASE = sys.argv[1] if len(sys.argv) > 1 else "/opt/data/cache/scratch/swordmod"
OUT_DB = sys.argv[2] if len(sys.argv) > 2 else "/opt/data/cache/scratch/bible.db"

mods = SwordModules(BASE)
mods.parse_modules()
bible = mods.get_bible_from_module('swhonmm2024eb')
struct = bible._structure
books_by_testament = struct.get_books()

# OSIS id -> Swahili display name (NRSV versification order used by this module)
SWAHILI_NAMES = {
    "Gen":"Mwanzo","Exod":"Kutoka","Lev":"Mambo ya Walawi","Num":"Hesabu","Deut":"Kumbukumbu la Torati",
    "Josh":"Yoshua","Judg":"Waamuzi","Ruth":"Ruthu","1Sam":"1 Samweli","2Sam":"2 Samweli",
    "1Kgs":"1 Wafalme","2Kgs":"2 Wafalme","1Chr":"1 Mambo ya Nyakati","2Chr":"2 Mambo ya Nyakati",
    "Ezra":"Ezra","Neh":"Nehemia","Esth":"Esta","Job":"Ayubu","Ps":"Zaburi","Prov":"Mithali",
    "Eccl":"Mhubiri","Song":"Wimbo Ulio Bora","Isa":"Isaya","Jer":"Yeremia","Lam":"Maombolezo",
    "Ezek":"Ezekieli","Dan":"Danieli","Hos":"Hosea","Joel":"Yoeli","Amos":"Amosi","Obad":"Obadia",
    "Jonah":"Yona","Mic":"Mika","Nah":"Nahumu","Hab":"Habakuki","Zeph":"Sefania","Hag":"Hagai",
    "Zech":"Zekaria","Mal":"Malaki",
    "Matt":"Mathayo","Mark":"Marko","Luke":"Luka","John":"Yohana","Acts":"Matendo ya Mitume",
    "Rom":"Warumi","1Cor":"1 Wakorintho","2Cor":"2 Wakorintho","Gal":"Wagalatia","Eph":"Waefeso",
    "Phil":"Wafilipi","Col":"Wakolosai","1Thess":"1 Wathesalonike","2Thess":"2 Wathesalonike",
    "1Tim":"1 Timotheo","2Tim":"2 Timotheo","Titus":"Tito","Phlm":"Filemoni","Heb":"Waebrania",
    "Jas":"Yakobo","1Pet":"1 Petro","2Pet":"2 Petro","1John":"1 Yohana","2John":"2 Yohana",
    "3John":"3 Yohana","Jude":"Yuda","Rev":"Ufunuo",
}

def strip_osis_markup(raw):
    if raw is None:
        return ""
    text = re.sub(r'<[^>]+>', '', raw)
    text = text.replace('\n', ' ').replace('\xad', '')
    text = re.sub(r'\s+', ' ', text).strip()
    return text

if os.path.exists(OUT_DB):
    os.remove(OUT_DB)
conn = sqlite3.connect(OUT_DB)
cur = conn.cursor()
cur.execute("""CREATE TABLE verses (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    book_osis TEXT, book_name TEXT, book_order INTEGER,
    chapter INTEGER, verse INTEGER, text TEXT
)""")
cur.execute("CREATE INDEX idx_verses_lookup ON verses(book_name, chapter, verse)")
cur.execute("CREATE INDEX idx_verses_osis ON verses(book_osis, chapter, verse)")
cur.execute("CREATE VIRTUAL TABLE verses_fts USING fts4(text, content='verses')")

order = 0
total_verses = 0
missing_books = []
for testament in ('ot', 'nt'):
    for book in books_by_testament[testament]:
        order += 1
        osis = book.osis_name
        swname = SWAHILI_NAMES.get(osis)
        if not swname:
            missing_books.append(osis)
            swname = book.name
        book_verse_count = 0
        for c_idx, nverse in enumerate(book.chapter_lengths, start=1):
            for v in range(1, nverse + 1):
                try:
                    raw = bible.get(books=[osis], chapters=[c_idx], verses=[v], clean=False)
                except Exception as e:
                    continue
                text = strip_osis_markup(raw)
                if not text:
                    continue
                cur.execute(
                    "INSERT INTO verses (book_osis, book_name, book_order, chapter, verse, text) VALUES (?,?,?,?,?,?)",
                    (osis, swname, order, c_idx, v, text),
                )
                book_verse_count += 1
                total_verses += 1
        print(f"{order:3d} {swname} ({osis}): {book.num_chapters} chapters, {book_verse_count} verses", flush=True)

conn.commit()
cur.execute("INSERT INTO verses_fts (rowid, text) SELECT id, text FROM verses")
conn.commit()
print("MISSING NAME MAPPINGS:", missing_books)
print("TOTAL VERSES:", total_verses)
cur.execute("SELECT COUNT(*) FROM verses")
print("DB row count:", cur.fetchone()[0])
cur.execute("SELECT book_name, chapter, verse, text FROM verses WHERE book_osis='John' AND chapter=3 AND verse=16")
print("John 3:16 check:", cur.fetchone())
conn.close()
print("DB size bytes:", os.path.getsize(OUT_DB))

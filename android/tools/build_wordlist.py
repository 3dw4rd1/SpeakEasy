"""Builds app/src/main/assets/words.txt, the keyboard's built-in word list.

Only needed when you want to change the list; the result is checked in to git.

    pip install spylls symspellpy
    npm pack dictionary-en-au && tar xzf dictionary-en-au-*.tgz      # gives ./package/
    python build_wordlist.py package/index <path to frequency_dictionary_en_82_765.txt> \
        ../app/src/main/assets/words.txt

Sources:
- How common each word is: SymSpell's English frequency list (MIT licence).
- Which spellings are right: the SCOWL Australian English dictionary (the closest to NZ
  English: colour, organise, tyre), so US-only spellings and junk are left out. It also knows
  which words take a capital (Auckland, Monday).

The output is one word per line, most common first.
"""
import sys

from spylls.hunspell import Dictionary

# Typed without the apostrophe, these are offered (and mostly autocorrected) with it.
CONTRACTIONS = """I'm I'll I've I'd don't doesn't didn't isn't aren't wasn't weren't haven't hasn't
hadn't won't wouldn't couldn't shouldn't can't it's that's what's there's here's let's he's
she's we're we've we'll we'd you're you've you'll you'd they're they've they'll they'd who's
how's where's ain't mustn't needn't would've could've should've it'll that'll o'clock""".split()

# Everyday NZ words the Australian dictionary doesn't have. Macrons are added by hand on the
# keyboard, so these are the plain-letter forms.
EXTRAS = """Aotearoa Kiwi Kiwis Maori Pakeha kia ora koutou tena koe morena whanau mahi kai
aroha koha whenua iwi hapu marae tangi hui kaumatua tamariki wahine tane waka pounamu haka
hangi mana tapu taonga tikanga korero karakia waiata kaupapa mokopuna tamaiti rangatira motu
maunga awa moana whare kura kohanga reo tangata ka pai nau mai haere ra ae kao
Auckland Wellington Christchurch Hamilton Tauranga Dunedin Napier Hastings Nelson Rotorua
Whangarei Invercargill Whanganui Gisborne Taupo Timaru Blenheim Queenstown Wanaka Kerikeri
Paihia Thames Whakatane Tokoroa Masterton Levin Porirua Petone Paraparaumu Kapiti Waikato
Taranaki Otago Canterbury Northland Coromandel Wairarapa Manawatu Marlborough Southland
Waitangi Matariki Anzac Kaikoura Hokitika Greymouth Westport Oamaru Ashburton Rangiora
Pukekohe Papakura Manukau Waitakere Takapuna Albany Remuera Ponsonby Parnell Onehunga
Melbourne Sydney Brisbane Perth Adelaide Canberra Hobart Darwin Geelong Ballarat Bendigo
jandals togs bach smoko chur heaps stoked munted chocka dairy ute tramping kumara
eftpos Xero app apps email emails online website wifi internet smartphone
ok okay yeah yep yup nah nope hmm haha hahaha lol omg btw fyi asap tbh idk imo thx pls plz ty
gonna wanna gotta kinda sorta dunno lemme gimme outta cos coz tho thru tmrw arvo servo bro
hey hiya oi eh aye yo ya nvm brb msg pic pics convo info vs etc u ur k kk xx xo NZ""".split()


def main(dic_base: str, freq_path: str, out_path: str, limit: int = 60000) -> None:
    spell = Dictionary.from_files(dic_base)
    words: list[str] = []
    seen: set[str] = set()

    def add(w: str) -> None:
        if w.lower() not in seen:
            seen.add(w.lower())
            words.append(w)

    for line in open(freq_path, encoding="utf-8"):
        w = line.split()[0]
        if len(words) == 150:            # contractions are very common: straight after the top words
            for c in CONTRACTIONS:
                add(c)
        if len(words) == 20000:          # NZ words: ahead of the rare English ones
            for e in EXTRAS:
                add(e)
        if len(words) >= limit:
            break
        if not w.isalpha() or (len(w) == 1 and w not in "ai"):
            continue
        if w == "i":
            add("I")
        elif spell.lookup(w):
            add(w)
        elif spell.lookup(w.capitalize()):
            add(w.capitalize())
        elif len(w) <= 5 and spell.lookup(w.upper()):
            add(w.upper())
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(words) + "\n")
    print(len(words), "words")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])

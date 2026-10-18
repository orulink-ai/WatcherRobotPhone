#!/usr/bin/env python3
"""Generate exact-parity fixtures for the on-device ModernBERT tokenizer (Privacy Mode).

The Kotlin tokenizer must reproduce, byte-for-byte, the token ids AND character offsets
produced by the reference Hugging Face `tokenizers` library for the ai4privacy ModernBERT
tokenizer. This script emits three fixture files, each a JSON array of:

    {"text": <str>, "ids": [<int>...], "offsets": [[start, end], ...]}

Offsets are emitted in **UTF-16 code-unit** space (Java/Kotlin `String` indexing) rather than
Python codepoint space, so the Kotlin parity test can compare directly. Special tokens
([CLS]/[SEP]) carry offset [0, 0] (the reference convention), which the Kotlin test maps to its
`null` offset.

Usage:
    python3 -m venv venv && venv/bin/pip install tokenizers
    venv/bin/python generate_tokenizer_fixtures.py <tokenizer.json> <out_dir>

Output is deterministic (fixed seed, stable case ordering).
"""
import json
import random
import sys

from tokenizers import Tokenizer


def cp_to_utf16(text, cp_offset):
    """Convert a Python codepoint offset into a UTF-16 code-unit offset."""
    units = 0
    for ch in text[:cp_offset]:
        units += 2 if ord(ch) > 0xFFFF else 1
    return units


def encode_case(tokenizer, text):
    enc = tokenizer.encode(text)
    offsets = [[cp_to_utf16(text, s), cp_to_utf16(text, e)] for (s, e) in enc.offsets]
    return {"text": text, "ids": list(enc.ids), "offsets": offsets}


def standard_cases():
    cases = [
        "", "a", "My name is Sarah Connor",
        "Je m'appelle Pierre Dubois",
        "Mein Name ist Hans Mueller",
        "Mi nombre es Maria Garcia",
        "Il mio nome e Giuseppe Verdi",
        "Mijn naam is Jan de Vries",
        "मेरा नाम राहुल है",
        "నా పేరు రాజు",
        "john.doe@example.com",
        "user+tag@mail.corp.example.co",
        "+39 333 123 4567",
        "(650) 253-0000",
        "4111 1111 1111 1111",
        "DE89370400440532013000",
        "42 Baker Street, London NW1 6XE",
        "card number: 4111 1111 1111 1111",
        "email: jane@example.org",
        "password: hunter2",
        "OK", "Cancel", "Submit", "Sign in",
        "The quick brown fox jumps over the lazy dog.",
        "Hello, world!",
        "Price: $1,234.56",
        "Order #12345 shipped on 2026-08-02",
    ]
    # Pad to >= 60 with generated label:value UI strings.
    labels = ["Name", "Email", "Phone", "Address", "City", "ZIP", "SSN", "Card"]
    values = ["John Smith", "a@b.co", "555-0100", "1 Main St", "Berlin", "10115", "078-05-1120", "4111111111111111"]
    for label in labels:
        for value in values:
            cases.append(f"{label}: {value}")
    return cases[:80]


def edge_cases():
    return [
        "|||EMAIL_ADDRESS|||",
        "before |||PHONE_NUMBER||| after",
        "text <|endoftext|> more",
        "start [MASK] end",
        "a" + " " * 2 + "b",
        "a" + " " * 5 + "b",
        "a" + " " * 24 + "b",
        "col1" + " " * 3 + "col2",
        "nbsp here",
        "enspace here",
        "ideographic　space",
        "zwsp​here",
        "café test",  # NFD -> NFC
        "déjà vu",  # NFD
        "éèê composed",  # NFC already
        "北京市 朝阳区",
        "東京都",
        "العربية نص",
        "Привет мир",
        "देवनागरी लिपि",
        "నమస్తే ప్రపంచం",
        "emoji 😀 face",
        "family 👨‍👩‍👧 zwj",
        "flag 🇮🇹 italy",
        "it's can't don't",
        "’it’s curly",
        "|||IP_ADDRESS||||||EMAIL_ADDRESS|<|endoftext|>[MASK]",
        "mixed北123abc",
        "  leading and trailing  ",
        "\ttab\tseparated\t",
        "line1\nline2\nline3",
        "MiXeD CaSe WoRdS",
        "UPPER lower 123 !!!",
        "a1b2c3d4e5f6",
        "....---___",
        "$#@!%^&*()",
        "verylongwordwithnobreakswhatsoeverherejustkeepsgoing",
        "©®™°±µ",
        "①②③ⅣⅤ",
        "́̀ leading combining",  # combining at start
        "trailing combining á",
        "super²script",
        "z" * 40,
        "1 " * 30,  # long token count -> exercises truncation path modestly
        "María José García-López",
        "O'Brien and D'Angelo",
        "北京市朝阳区建国路88号",
        "श्रीमान राहुल शर्मा",
        "السيد أحمد محمد",
        "Ελληνικά κείμενο",
        "한국어 텍스트입니다",
        "ภาษาไทย ทดสอบ",
        "Tiếng Việt có dấu",
        "Ｆｕｌｌｗｉｄｔｈ ＡＢＣ",
        "iban IT60X0542811101000000123456 here",
        "ssn 078-05-1120 please",
        "visa 4111-1111-1111-1111 exp",
        "call +44 20 7946 0000 now",
        "мой адрес a@b.ru здесь",
        "rtl mix עברית and english",
        "price €1.234,56 total",
        "mixed |||EMAIL_ADDRESS||| and 4111111111111111",
        "tab\tand newline\ntogether",
    ]


def fuzz_cases(count=500):
    rng = random.Random(57)
    pools = [
        list("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"),
        list("0123456789"),
        list(" \t  　​"),
        list("北京上海广州深圳東京大阪"),
        list("देवनागरीलिपि"),
        list("العربيةنص"),
        list("😀😁🎉🚀🇮🇹"),
        list(".,!?;:@#%&*()-_+=/"),
        ["|||EMAIL_ADDRESS|||", "|||PHONE_NUMBER|||", "<|endoftext|>", "[MASK]", "|||IP_ADDRESS|||"],
        ["'s", "'t", "'re", "'ll", "’"],
    ]
    cases = []
    for _ in range(count):
        parts = []
        for _ in range(rng.randint(1, 12)):
            pool = rng.choice(pools)
            token = rng.choice(pool)
            parts.append(token)
            if rng.random() < 0.4:
                parts.append(" ")
        cases.append("".join(parts))
    return cases


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    tokenizer_path, out_dir = sys.argv[1], sys.argv[2]
    tokenizer = Tokenizer.from_file(tokenizer_path)

    generators = {
        "standard": standard_cases(),
        "edge_cases": edge_cases(),
        "fuzz": fuzz_cases(),
    }
    for name, cases in generators.items():
        data = [encode_case(tokenizer, text) for text in cases]
        out_path = f"{out_dir}/{name}.json"
        with open(out_path, "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False)
        print(f"wrote {out_path}: {len(data)} cases")


if __name__ == "__main__":
    main()

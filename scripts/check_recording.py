"""Checks a recording before it's published.

usage: python3 scripts/check_recording.py recording.jsonl

It must be something cm-replay can serve: an OPEN line, the store's cosmetics, and the reply to the recorder's
request. It must hold nothing about the account: no chat, social or profile packets (the recorder's allowlist), no
unlocked cosmetics, at most one outfit and emote wheel, both blank, and no skin but Steve's.
"""
import json
import re
import sys

STEVE = "1a4af718455d4aab528e7a61f86fa25e6a369d1768dcb13f7df319a713eb810b"
KEEP_PREFIXES = ("cosmetic.", "wardrobe.", "features.ServerDisabledFeaturesPacket", "currency.",
                 "coins.ServerCoinBundleOptionsPacket", "coins.ClientCoinBundleOptionsPacket")


def fail(message):
    sys.exit(f"recording rejected: {message}")


def main(path):
    with open(path, encoding="utf-8") as f:
        lines = [json.loads(line) for line in f if line.strip()]
    if not lines or lines[0].get("type") != "OPEN":
        fail("no OPEN line first")
    cosmetics = 0
    answered = False
    requests = {p.get("id") for p in lines if p.get("type") == "SEND"}
    for p in lines[1:]:
        name, payload = p.get("name", ""), p.get("payload") or {}
        if p.get("type") == "SEND" and not name.startswith(KEEP_PREFIXES):
            fail(f"a {name} request")
        if p.get("type") == "RECV" and not name.startswith(KEEP_PREFIXES) and p.get("id") not in requests:
            fail(f"a {name} packet")
        if name == "cosmetic.ServerCosmeticsPopulatePacket":
            cosmetics += len(payload.get("a") or [])
            answered |= bool(p.get("id")) and p.get("id") in requests
        if name == "cosmetic.ServerCosmeticsUserUnlockedPacket" and any(payload.values()):
            fail("unlocked cosmetics in it")
        if name == "cosmetic.outfit.ServerCosmeticOutfitPopulatePacket":
            outfits = payload.get("outfits") or []
            if len(outfits) > 1 or any(o.get("a") != "1" or o.get("d") for o in outfits):
                fail("the account's outfits in it")
        if name == "cosmetic.emote.ServerCosmeticEmoteWheelPopulatePacket":
            wheels = payload.get("a") or []
            if len(wheels) > 1 or any(w.get("a") != "1" or w.get("c") for w in wheels):
                fail("the account's emote wheels in it")
    raw = open(path, encoding="utf-8").read()
    for skin in set(re.findall(r'"\d;([0-9a-f]{64})"', raw)):
        if skin != STEVE:
            fail(f"a skin texture {skin[:12]}... in it")
    if cosmetics < 100:
        fail(f"only {cosmetics} cosmetics in it")
    if not answered:
        fail("no reply to the recorder's cosmetics request")
    print(f"recording ok: {len(lines)} lines, {cosmetics} cosmetics, protocol {lines[0].get('version')}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])

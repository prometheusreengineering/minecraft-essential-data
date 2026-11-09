"""Adds every cosmetic id in a recording to cosmetics.json.

usage: python3 scripts/harvest.py recording.jsonl cosmetics.json

The recording holds the store's cosmetics (pushed on connect) and the ones the recorder asked for. Ids already in
the list stay, even once the store drops them: keeping retired cosmetics requestable is what the list is for. The
same filter as the patch's (EssentialCosmeticsManager.isValidCosmeticId) keeps generated ids and Essential's
checkout placeholder out. Prints the new ids, one per line; the file is rewritten only when there are some.
"""
import json
import re
import sys

POPULATE = "cosmetic.ServerCosmeticsPopulatePacket"
VALID = re.compile(r"[A-Z0-9_]+")
EXCLUDED = {"ESSENTIAL_PURCHASE_CONFIRMATION"}


def recorded_ids(path):
    ids = set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            packet = json.loads(line)
            if packet.get("type") != "RECV" or packet.get("name") != POPULATE:
                continue
            for cosmetic in (packet.get("payload") or {}).get("a") or []:
                cid = cosmetic.get("a") if isinstance(cosmetic, dict) else None
                if isinstance(cid, str) and VALID.fullmatch(cid) and cid.upper() not in EXCLUDED:
                    ids.add(cid)
    return ids


def main(recording, list_path):
    with open(list_path, encoding="utf-8") as f:
        known = json.load(f)
    seen = recorded_ids(recording)
    if not seen:
        sys.exit(f"{recording} has no cosmetics; refusing to harvest from it")
    new = sorted(seen - set(known))
    if new:
        with open(list_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(json.dumps(sorted(set(known) | seen), indent=4) + "\n")
    for cid in new:
        print(cid)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])

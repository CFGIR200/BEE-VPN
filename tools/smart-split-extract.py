#!/usr/bin/env python3
"""Extract fragment profiles from patterniha's Serverless-for-Iran subscription
into the BEE-VPN mirror format. Used both to build the initial mirror/seed and
(inline) by the smart-split-sync workflow.

Profiles the field has measured dead on Iranian carriers (fragA) are dropped
here, in the mirror pipeline — NOT in the app. That is the whole point of the
mirror arrangement: which profiles the fleet tries is GitHub-editable data, so
changing the set reaches every installed app with no release. Add or remove a
name in WORKING_PROFILES and let the workflow commit it.
"""
import json, sys, urllib.request, datetime

SUB_URL = ("https://raw.githubusercontent.com/patterniha/Serverless-for-Iran/"
           "refs/heads/main/Subscription/Serverless-for-Iran.json")

# The field measured fragA dead on Iranian carriers (محسن, 2026-09-12), so only
# fragB is mirrored. Upstream renames its configs between versions (v50 -> v51
# on 2026-09-20), so matching a full name breaks the sync on every bump and
# takes Smart Split offline for every installed app until someone edits this
# file. Matching the fragB suffix instead tracks the version automatically.
WORKING_PROFILE_SUFFIX = "fragB"

def _is_frag_b(name):
    # "Serverless-v51-fragB" -> keep; "Serverless-v51-fragA" -> drop.
    return name.endswith(WORKING_PROFILE_SUFFIX)

def extract(body_text):
    src = json.loads(body_text)
    if not isinstance(src, list):
        raise ValueError("subscription is not a JSON list")
    profiles = []
    for cfg in src:
        if not isinstance(cfg, dict):
            continue
        name = cfg.get("remarks", "")
        if not _is_frag_b(name):
            continue
        masks = None
        for ob in cfg.get("outbounds", []):
            if ob.get("tag") == "tcp-fragment-tls":
                fm = (ob.get("streamSettings") or {}).get("finalmask") or {}
                masks = fm.get("tcp")
                break
        if not masks:
            raise ValueError("no tcp-fragment-tls masks in %r" % name)
        # Upstream v58 (2026-11-05) added a new mask type — "merge" (a small-
        # write batching layer) — alongside the fragments. The app copies
        # finalmask.tcp verbatim into the Xray config (ShardConfigs.kt), and
        # the fleet has only ever been served fragment-only mirrors (v51..v55).
        # Whitelist fragments, drop anything else: a mask type the bundled
        # core may not parse is not worth the fleet's Smart Split.
        kept = [m for m in masks
                if m.get("type") == "fragment" and isinstance(m.get("settings"), dict)]
        dropped = len(masks) - len(kept)
        if dropped:
            print("note: %s: dropped %d non-fragment mask(s) (%s)"
                  % (name, dropped,
                     ", ".join(sorted({m.get("type", "?") for m in masks
                                       if not (m.get("type") == "fragment"
                                               and isinstance(m.get("settings"), dict))}))),
                  file=sys.stderr)
        if not kept:
            # A fragB profile whose fragments are all gone is useless to us;
            # skip it rather than fail the whole sync. If EVERY profile dies
            # this way the "no profiles survived" error below fires anyway.
            print("note: %s: no fragment masks left, skipping profile" % name,
                  file=sys.stderr)
            continue
        profiles.append({"name": name, "masks": kept})
    if not profiles:
        # Upstream renames its configs between versions (v50 -> v51 on
        # 2026-09-20). A hard failure here takes Smart Split offline for every
        # installed app until someone edits this file, which can be days.
        # Falling back to the previous good mirror keeps the fleet served
        # while the remarks list is updated; the sync is best-effort data, not
        # a build step.
        names = sorted({c.get("remarks", "") for c in src if isinstance(c, dict)})
        raise ValueError(
            "no profiles survived the working-profile filter. "
            "Upstream remarks are now: %s. Update WORKING_PROFILES to match."
            % names
        )
    return {
        "version": 1,
        "updated": datetime.datetime.now(datetime.timezone.utc)
                   .strftime("%Y-%m-%dT%H:%MZ"),
        "source": "patterniha/Serverless-for-Iran",
        "profiles": profiles,
    }

if __name__ == "__main__":
    req = urllib.request.Request(SUB_URL, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        mirror = extract(r.read().decode())
    json.dump(mirror, open(sys.argv[1], "w"), indent=2, ensure_ascii=False)
    print("wrote %d profiles: %s" % (len(mirror["profiles"]),
          ", ".join(p["name"] for p in mirror["profiles"])))

#!/usr/bin/env python3
"""Genera las listas del bloqueador que van dentro de la app.

Uso:  python3 tools/build_blocklists.py

Crea en app/src/main/assets/:
  - blocklist.bin            dominios bloqueados (hash FNV-1a de 64 bits, ordenados)
  - cosmetic_generic.txt     selectores CSS que se ocultan en todas las webs
  - cosmetic_specific.txt    reglas por web ("dominios##selector" y excepciones "dominio#@#selector")

Fuentes: HaGeZi Multi PRO++ (GPL-3.0),
EasyList / EasyList Adult / EasyPrivacy (GPL-3.0 / CC BY-SA 3.0).
"""
import re
import struct
import urllib.request
from pathlib import Path

ASSETS = Path(__file__).resolve().parent.parent / "app/src/main/assets"
EL = "https://raw.githubusercontent.com/easylist/easylist/master/"

DOMAIN_SOURCES = {
    # StevenBlack hosts se quitó: mezcla listas con licencia no comercial (CC BY-NC-SA).
    "hosts": [],
    "plain": ["https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro.plus-onlydomains.txt"],
    "abp": [
        EL + "easylist/easylist_adservers.txt",
        EL + "easylist_adult/adult_adservers.txt",
        EL + "easyprivacy/easyprivacy_trackingservers.txt",
    ],
}
GENERIC_HIDE = [EL + "easylist/easylist_general_hide.txt"]
SPECIFIC_HIDE = [EL + "easylist/easylist_specific_hide.txt", EL + "easylist_adult/adult_specific_hide.txt"]

# Nunca bloquear estos dominios exactos aunque una lista los incluya
# (romperían demasiadas webs o el propio buscador).
NEVER_BLOCK = {
    "duckduckgo.com", "googlevideo.com", "gstatic.com", "googleapis.com", "cloudflare.com",
    "cloudfront.net", "akamaihd.net", "akamaized.net", "jsdelivr.net", "fastly.net",
    "cdnjs.cloudflare.com", "jwplayer.com", "jwpcdn.com", "vjs.zencdn.net",
}

DOMAIN_RE = re.compile(r"^[a-z0-9_-]+(\.[a-z0-9_-]+)+$")
ABP_DOMAIN_RE = re.compile(r"^\|\|([a-z0-9._-]+)\^(\$(.*))?$")
SAFE_ABP_OPTIONS = {"third-party", "3p", "all", "script", "image", "popup", "subdocument", "xmlhttprequest", "media", "object"}
UNSUPPORTED_COSMETIC = (":-abp-", ":has-text", ":xpath", ":style(", ":upward", ":remove", ":matches-", ":min-text-length", ":watch-attr", ":others(")


def fetch(url):
    print("Descargando", url)
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read().decode("utf-8", "replace").splitlines()


def fnv1a64(text):
    h = 0xCBF29CE484222325
    for b in text.encode("utf-8"):
        h ^= b
        h = (h * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h - (1 << 64) if h >= (1 << 63) else h  # como Long de Java/Kotlin


def collect_domains():
    domains = set()
    for url in DOMAIN_SOURCES["hosts"]:
        for line in fetch(url):
            parts = line.split()
            if len(parts) >= 2 and parts[0] in ("0.0.0.0", "127.0.0.1"):
                domains.add(parts[1].lower())
    for url in DOMAIN_SOURCES["plain"]:
        for line in fetch(url):
            line = line.strip().lower()
            if line and not line.startswith("#"):
                domains.add(line)
    for url in DOMAIN_SOURCES["abp"]:
        for line in fetch(url):
            m = ABP_DOMAIN_RE.match(line.strip().lower())
            if not m:
                continue
            options = set(filter(None, (m.group(3) or "").split(",")))
            if options <= SAFE_ABP_OPTIONS:
                domains.add(m.group(1))
    domains = {d.strip(".") for d in domains if DOMAIN_RE.match(d.strip("."))}
    domains -= NEVER_BLOCK
    domains -= {"localhost", "0.0.0.0", "local", "broadcasthost"}
    # Quitar subdominios cuyo dominio padre ya está bloqueado.
    def covered(d):
        parts = d.split(".")
        return any(".".join(parts[i:]) in domains for i in range(1, len(parts) - 1))
    return sorted(d for d in domains if not covered(d))


def valid_selector(sel):
    return sel and not any(x in sel for x in UNSUPPORTED_COSMETIC) and "{" not in sel and "}" not in sel


def collect_cosmetic():
    generic = []
    for url in GENERIC_HIDE:
        for line in fetch(url):
            line = line.strip()
            if line.startswith("##") and valid_selector(line[2:]):
                generic.append(line[2:])
    specific = []
    for url in SPECIFIC_HIDE:
        for line in fetch(url):
            line = line.strip()
            if not line or line.startswith("!") or "#?#" in line or "#$#" in line:
                continue
            m = re.match(r"^([^#]+)(##|#@#)(.+)$", line)
            if not m:
                continue
            domains = [d for d in m.group(1).split(",") if d and not d.startswith("~")]
            if domains and valid_selector(m.group(3)):
                specific.append(",".join(domains) + m.group(2) + m.group(3))
    return sorted(set(generic)), sorted(set(specific))


def main():
    domains = collect_domains()
    hashes = sorted({fnv1a64(d) for d in domains})
    (ASSETS / "blocklist.bin").write_bytes(struct.pack(">%dq" % len(hashes), *hashes))
    generic, specific = collect_cosmetic()
    (ASSETS / "cosmetic_generic.txt").write_text("\n".join(generic) + "\n", encoding="utf-8")
    (ASSETS / "cosmetic_specific.txt").write_text("\n".join(specific) + "\n", encoding="utf-8")
    old = ASSETS / "blocklist.txt"
    if old.exists():
        old.unlink()
    print(f"{len(hashes)} dominios, {len(generic)} reglas genéricas, {len(specific)} reglas por web")


if __name__ == "__main__":
    main()

import zipfile, struct, sys, io

ZIP = r"D:\Documents\workbuddy\project\SqlPoint\build\slow-sql-locator.zip"
REPORT = r"D:\Documents\workbuddy\project\SqlPoint\verify_report.txt"
ok = True
lines = []

def log(s):
    lines.append(s)

with zipfile.ZipFile(ZIP) as z:
    names = z.namelist()
    log("ZIP entries: %d" % len(names))
    px = z.read("slow-sql-locator/META-INF/plugin.xml").decode("utf-8", "ignore")
    for l in px.splitlines():
        if "<version>" in l:
            log("plugin.xml " + l.strip())
    log("has v1.7 note: %s" % ("v1.7" in px))
    if "v1.7" not in px:
        ok = False
    jarname = "slow-sql-locator/lib/sql-locator-core.jar"
    if jarname not in names:
        log("ERROR: core jar missing"); ok = False
    else:
        with z.open(jarname) as jf:
            jdata = jf.read()
        with zipfile.ZipFile(io.BytesIO(jdata)) as jz:
            jnames = jz.namelist()
            for need in ["com/loc/LocateSqlAction.class",
                         "com/loc/SqlLocatorToolWindowFactory.class",
                         "com/loc/LocatorEngine.class"]:
                present = need in jnames
                log("%s in jar: %s" % (need, present))
                if not present:
                    ok = False
            with jz.open("com/loc/LocateSqlAction.class") as cf:
                head = cf.read(8)
            magic, minor, major = struct.unpack(">IHH", head)
            log("LocateSqlAction.class major version: %d (52=Java8)" % major)
            if major != 52:
                ok = False
            data = jz.read("com/loc/LocateSqlAction.class")
            rip = b"rebuildIndex" in data
            log("rebuildIndex in LocateSqlAction.class: %s" % rip)
            if not rip:
                ok = False
            tw = jz.read("com/loc/SqlLocatorToolWindowFactory.class")
            rb = "\u91cd\u5efa\u7d22\u5f15".encode("utf-8")
            log("rebuild button text in toolwindow: %s" % (rb in tw))
            if rb not in tw:
                ok = False

log("RESULT: %s" % ("PASS" if ok else "FAIL"))
with open(REPORT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
sys.exit(0 if ok else 1)

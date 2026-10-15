import zipfile, io, sys

root = r"D:\Documents\workbuddy\project\SqlPoint"
zip_path = root + r"\build\slow-sql-locator.zip"

z = zipfile.ZipFile(zip_path)
print("=== outer zip entries ===")
for n in z.namelist():
    print(" ", n)

xml = z.read("slow-sql-locator/META-INF/plugin.xml").decode("utf-8")
print("plugin.xml version:", [l for l in xml.splitlines() if "<version>" in l])
print("has 版本变更:", "版本变更" in xml)
print("has 匹配程度:", "匹配程度" in xml)
print("has ctrl alt Q:", "ctrl alt Q" in xml)

jar_bytes = z.read("slow-sql-locator/lib/sql-locator-core.jar")
z2 = zipfile.ZipFile(io.BytesIO(jar_bytes))
print("=== core jar entries (com/loc) ===")
for n in z2.namelist():
    if n.startswith("com/loc/"):
        print(" ", n)

needle = "匹配度".encode("utf-8")
cls = z2.read("com/loc/LocateSqlAction.class")
print("LocateSqlAction.class 匹配度 present:", needle in cls)
# also confirm other v1.6 classes exist
print("has SqlLocatorToolWindowFactory:", "com/loc/SqlLocatorToolWindowFactory.class" in z2.namelist())
print("has SqlLocatorHistory:", "com/loc/SqlLocatorHistory.class" in z2.namelist())

import zipfile, io, struct

root = r"D:\Documents\workbuddy\project\SqlPoint"
z = zipfile.ZipFile(root + r"\build\slow-sql-locator.zip")
jar = zipfile.ZipFile(io.BytesIO(z.read("slow-sql-locator/lib/sql-locator-core.jar")))
for name in ["com/loc/LocateSqlAction.class", "com/loc/SqlLocator.class", "com/loc/Fingerprint.class"]:
    data = jar.read(name)
    magic, minor, major = struct.unpack(">IHH", data[:8])
    print(name, "magic=0x%X" % magic, "major=%d" % major, "(52=Java8)" if major == 52 else "WRONG")

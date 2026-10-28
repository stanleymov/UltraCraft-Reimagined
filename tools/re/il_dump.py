"""Print the CIL of chosen methods in Assembly-CSharp.dll, with tokens resolved to names (for reading exact constants).

    python -I tools/re/il_dump.py Type.Method [Type.Method ...]   (Type.* = every method of Type)
Reads the user's own install; prints to the console only (nothing is saved or shipped).
"""
import sys

import dnfile
from dncil.cil.body import CilMethodBody
from dncil.cil.body.reader import CilMethodBodyReaderBase
from dncil.cil.error import MethodBodyFormatError
from dncil.clr.token import Token, StringToken, InvalidToken

DLL = r"C:\Program Files (x86)\Steam\steamapps\common\ULTRAKILL\ULTRAKILL_Data\Managed\Assembly-CSharp.dll"


class Reader(CilMethodBodyReaderBase):
    def __init__(self, pe, row):
        self.pe = pe
        self.offset = pe.get_offset_from_rva(row.Rva)

    def read(self, n):
        data = self.pe.get_data(self.pe.get_rva_from_offset(self.offset), n)
        self.offset += n
        return data

    def tell(self):
        return self.offset

    def seek(self, off):
        self.offset = off
        return off


def tables(pe):
    t = pe.net.mdtables
    return t


def type_of_method(pe):
    """method row index (1-based) -> declaring type name"""
    t = tables(pe)
    out = {}
    tds = list(t.TypeDef)
    for i, td in enumerate(tds):
        start = td.MethodList[0].row_index if td.MethodList else None
        for m in td.MethodList:
            out[m.row_index] = (str(td.TypeNamespace) + "." if str(td.TypeNamespace) else "") + str(td.TypeName)
    return out


def resolve(pe, tok, owners):
    t = tables(pe)
    try:
        if isinstance(tok, StringToken):
            return repr(pe.net.user_strings.get(tok.rid).value)
        table, rid = tok.table, tok.rid
        if table == 0x06:
            r = t.MethodDef[rid - 1]
            return f"{owners.get(rid, '?')}.{r.Name}"
        if table == 0x04:
            r = t.Field[rid - 1]
            return f"field {r.Name}"
        if table == 0x0A:
            r = t.MemberRef[rid - 1]
            cls = r.Class.row
            cname = getattr(cls, "TypeName", None) or getattr(cls, "Name", "?")
            return f"{cname}.{r.Name}"
        if table == 0x2B:
            r = t.MethodSpec[rid - 1]
            m = r.Method.row
            return f"spec {m.Name}"
        if table in (0x01, 0x02):
            r = (t.TypeRef if table == 1 else t.TypeDef)[rid - 1]
            return f"type {r.TypeName}"
    except Exception as e:  # noqa: BLE001
        return f"tok {tok} ({e})"
    return f"tok {tok}"


def main():
    pe = dnfile.dnPE(DLL)
    t = tables(pe)
    owners = type_of_method(pe)
    want = set(sys.argv[1:])
    for idx, row in enumerate(t.MethodDef, 1):
        full = f"{owners.get(idx, '?')}.{row.Name}"
        if not row.Rva or not ('ALL' in want or full in want or any(w.endswith('.*') and full.startswith(w[:-1]) for w in want)):
            continue
        try:
            body = CilMethodBody(Reader(pe, row))
        except MethodBodyFormatError as e:
            print(full, "ERR", e)
            continue
        print("==", full)
        for insn in body.instructions:
            op = insn.operand
            s = ""
            if isinstance(op, (Token, StringToken)) and not isinstance(op, InvalidToken):
                s = resolve(pe, op, owners)
            elif op is not None:
                s = str(op)
            print(f"  {insn.offset:5x} {insn.opcode.name:<12} {s}")


if __name__ == "__main__":
    main()

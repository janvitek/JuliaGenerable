package prlprg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

interface Type {

    // Creates the simpler format
    GenDB.Ty toTy();

    // Type names can include string and numbers and concatenated vars
    static String parseTypeName(Parser p) {
        var tok = p.take();
        if (tok.is("typeof") || tok.is("keytype")) {
            var str = tok.toString();
            var q = p.sliceMatchedDelims("(", ")");
            if (q.isEmpty()) {
                return str; // typeof or keytype without parens (odd?)
            }
            while (q.isEmpty()) {
                str += q.take().toString();
            }
            return "(" + str + " )";
        } else {
            var nm = tok.toString();
            if (nm.startsWith("\"") && nm.endsWith("\"")) {
                return nm; // A quoted string, leave it as is.
            } else {
                return nm.replaceAll("\"", ""); // Get rid of quotes in case of MIME"xyz"
            }
        }
    }

    // Functions can inlucde concatenated vars as well as parenthesized stuff.
    static String parseFunctionName(Parser p) {
        var q = p.sliceMatchedDelims("(", ")"); // for parenthesized funtion names
        if (q.isEmpty()) {
            return p.take().toString().replaceAll("\"", "");  // Get rid of quotes in case of "fun"
        }
        var str = "";
        while (!q.isEmpty()) {
            str += q.take().toString();
        }
        return str;
    }

}

// An instance of a datatype constructor (not a union all or bound var).
record TypeInst(String nm, List<Type> ps) implements Type {

    static Type parse(Parser p) {
        var name = Type.parseTypeName(p);
        List<Type> params = new ArrayList<>();
        var q = p.sliceMatchedDelims("{", "}");
        while (!q.isEmpty()) {
            params.add(BoundVar.parse(q.sliceNextCommaOrSemi()));
        }
        return new TypeInst(name, params);
    }

    static Pattern pattern = Pattern.compile("[-+]?\\d*\\.?\\d+([eE][-+]?\\d+)?");

    // An instance of a datatype with zero or more type parameters.
    // In the case the nm is Union or Tuple, create the specific types.
    @Override
    public GenDB.Ty toTy() {
        if (ps.isEmpty() && (nm.startsWith(":") || nm.startsWith("\"") || pattern.matcher(nm).matches())) {
            return new GenDB.TyCon(nm);
        }
        var tys = ps.stream().map(tt -> tt.toTy()).collect(Collectors.toList());
        return nm.equals("Tuple") ? new GenDB.TyTuple(tys)
                : nm.equals("Union") ? new GenDB.TyUnion(tys)
                : new GenDB.TyInst(nm, tys);
    }

    @Override
    public String toString() {
        return nm + (ps.isEmpty() ? ""
                : "{" + ps.stream().map(Object::toString).collect(Collectors.joining(", ")) + "}");
    }
}

// A variable with optional upper and lower bounds. At this point we are still a little shaky
// on which is which, so we parse all of them to Type. Also, if we get an implicit variable
// name, we create a fesh name for it. thwse names start with a question mark.
record BoundVar(Type name, Type lower, Type upper) implements Type {

    static int gen = 0;

    // can get ::  T   |   T <: U   |   L <: T <: U
    static Type parse(Parser p) {
        if (p.has("<:")) {
            var t = UnionAllInst.parse(p.drop());
            var fresh = new TypeInst("?" + ++gen, new ArrayList<>());
            return new BoundVar(fresh, null, t);
        }
        var t = UnionAllInst.parse(p);
        if (p.has("<:")) {
            var u = UnionAllInst.parse(p.drop());
            if (p.has("<:")) {
                var l = UnionAllInst.parse(p.drop());
                return new BoundVar(l, t, u);
            } else {
                return new BoundVar(t, null, u);
            }
        } else {
            return t;
        }
    }

    // Create a bound variable, the default for lower and upper bouds are none (Union{}) and Any.
    @Override
    public GenDB.Ty toTy() {
        return new GenDB.TyVar(name.toString(), lower == null ? GenDB.Ty.none() : lower.toTy(),
                (upper == null || upper.toString().equals("Any")) ? GenDB.Ty.any() : upper.toTy());
    }

    @Override
    public String toString() {
        return (lower != null ? lower + " <: " : "") + name.toString() + (upper != null ? " <: " + upper : "");
    }
}

// Int{X} where {Z <: X <: Y, Y <: Int} where {X <: Int}
record UnionAllInst(Type body, List<Type> bounds) implements Type {

    static Type parse(Parser p) {
        var type = TypeInst.parse(p);
        if (!p.has("where")) {
            return type;
        }
        if (p.drop().has("(")) {
            p = p.sliceMatchedDelims("(", ")");
        }
        var boundVars = new ArrayList<Type>();
        while (!p.isEmpty()) {
            boundVars.add(BoundVar.parse(p));
            if (p.has(",")) {
                p.drop().failIfEmpty("Missing type parameter", p.peek());

            } else {
                break;
            }
        }
        return new UnionAllInst(type, boundVars);
    }

    @Override
    public GenDB.Ty toTy() {
        var ty = body.toTy();
        var it = bounds.listIterator(bounds.size());
        while (it.hasPrevious()) { // Going backwards, building Exists inside out
            ty = new GenDB.TyExist(it.previous().toTy(), ty);
        }
        return ty;
    }

    @Override
    public String toString() {
        return body.toString() + (bounds.isEmpty() ? ""
                : " where " + bounds.stream().map(Object::toString).collect(Collectors.joining(", ")));
    }
}

record TypeDeclaration(String modifiers, String nm, List<Type> ps, Type parent, String src) {

    static String parseModifiers(Parser p) {
        var str = "";
        if (p.has("(")) {
            if (!p.drop().take().is("closure")) {
                p.failAt("Expected 'closure'", p.peek());
            }
            if (!p.take().is(")")) {
                p.failAt("Expected ')'", p.peek());
            }
            str += "(closure) ";
        }
        while (p.has("abstract") || p.has("primitive") || p.has("struct") || p.has("type")
                || p.has("mutable")) {
            str += p.take().toString() + " ";
        }
        return str;
    }

    static TypeDeclaration parse(Parser p) {
        var modifiers = parseModifiers(p);
        var name = Type.parseTypeName(p);
        var src = p.last.getLine();
        var ps = new ArrayList<Type>();
        var q = p.sliceMatchedDelims("{", "}");
        while (!q.isEmpty()) {
            ps.add(BoundVar.parse(q.sliceNextCommaOrSemi()));
        }
        if (p.peek().isNumber()) {
            p.drop();
            if (!modifiers.contains("primitive")) {
                p.failAt("Expected 'primitive'", p.peek());
            }
        }
        Type parent = null;
        if (p.has("<:")) {
            p.drop();
            parent = UnionAllInst.parse(p);
        }
        p.drop();
        p.sliceMatchedDelims("(", ")");
        return new TypeDeclaration(modifiers, name, ps, parent, src);
    }

    GenDB.TyDecl toTy() {
        var parentTy = (parent == null || parent.toString().equals("Any")) ? GenDB.Ty.any() : parent.toTy();
        var args = ps.stream().map(tt -> tt.toTy()).collect(Collectors.toList());
        var ty = new GenDB.TyInst(nm, args);
        return new GenDB.TyDecl(modifiers, nm, ty, parentTy, src);
    }

    @Override
    public String toString() {
        return modifiers + nm
                + (ps.isEmpty() ? "" : ("{" + ps.stream().map(Object::toString).collect(Collectors.joining(", ")) + "}"))
                + (parent == null ? "" : (" <: " + parent));
    }
}

record Param(String nm, Type ty, boolean varargs) {

    // can be:  x   |  x :: T   |  :: T   |   x...  |   :: T...
    static Param parse(Parser p) {
        var tok = p.take();
        var name = tok.is("::") ? "?NA" : tok.toString();
        var type = tok.is("::") ? UnionAllInst.parse(p) : null;
        var varargs = p.has("...") ? p.take().is("...") : false;
        if (type == null && p.has("::")) {
            type = UnionAllInst.parse(p.drop());
            varargs = p.has("...") ? p.take().is("...") : false;
        }
        return new Param(name, type, varargs);
    }

    GenDB.Ty toTy() {
        return ty == null ? GenDB.Ty.any() : ty.toTy();
    }

    @Override
    public String toString() {
        return nm + (ty != null ? " :: " + ty.toString() : "") + (varargs ? "..." : "");
    }
}

record Function(String nm, List<Param> ps, List<Type> wheres, String src) {

    static List<Type> parseWhere(Parser p) {
        var wheres = new ArrayList<Type>();
        if (!p.has("where")) {
            return wheres;
        }
        p.drop();
        if (p.has("{")) {
            p = p.sliceMatchedDelims("{", "}");  // we only need to read the where clause
        }
        while (!p.isEmpty()) {
            wheres.add(BoundVar.parse(p));
            if (p.has(",")) {
                p.drop().failIfEmpty("Missing type parameter", p.peek());
            } else if (p.has("in")) {
                break;
            }
        }
        return wheres;
    }

    static Function parse(Parser p) {
        if (p.has("function")) {
            p.drop();
        }
        var name = Type.parseFunctionName(p);
        var source = p.last.getLine();
        var q = p.sliceMatchedDelims("(", ")");
        var params = new ArrayList<Param>();
        while (!q.isEmpty()) {
            var r = q.sliceNextCommaOrSemi();
            if (r.isEmpty()) {
                break;
            }
            params.add(Param.parse(r));
        }
        var wheres = parseWhere(p);
        if (p.has("in")) {
            p.drop();
            while (!p.has(")")) {
                p.drop();
            }
            p.drop();
        }
        return new Function(name, params, wheres, source);
    }

    GenDB.TySig toTy() {
        List<GenDB.Ty> tys = ps.stream().map(Param::toTy).collect(Collectors.toList());
        var reverse = wheres.reversed();
        GenDB.Ty nty = new GenDB.TyTuple(tys);
        for (var where : reverse) {
            nty = new GenDB.TyExist(where.toTy(), nty);
        }
        return new GenDB.TySig(nm, nty, src);
    }

    @Override
    public String toString() {
        return nm + "(" + ps.stream().map(Object::toString).collect(Collectors.joining(", ")) + ")"
                + (wheres.isEmpty() ? ""
                : (" where " + wheres.stream().map(Object::toString).collect(Collectors.joining(", "))));
    }
}

public class Parser {

    private List<Lex.Tok> toks = new ArrayList<>();
    Lex.Tok last;

    public Parser withFile(String path) {
        try {
            List<String> ls = Files.readAllLines(Path.of(path));
            toks = new Lex(ls.toArray(new String[0])).tokenize();
            return this;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Parser withString(String s) {
        toks = new Lex(s.split("\n")).tokenize();
        return this;
    }

    enum Kind {
        DELIMITER, IDENTIFIER, STRING, EOF;
    }

    class Lex {

        String[] lns;
        int pos, off;
        static char[] delimiters = {'{', '}', ':', ',', ';', '=', '(', ')', '[', ']', '#', '<'};

        Lex(String[] lns) {
            this.lns = lns;
        }

        List<Tok> tokenize() {
            List<Tok> toks = new ArrayList<>();
            while (true) {
                var tok = next();
                if (tok.k == Kind.EOF) {
                    break;
                }
                toks.add(tok);
            }
            while (true) {
                var ntoks = combineTokens(new ArrayList<>(toks));
                if (toks.size() == ntoks.size()) {
                    return splitOffThreeDots(toks);
                }
                toks = ntoks;
            }
        }

        List<Tok> splitOffThreeDots(List<Tok> toks) {
            var ntoks = new ArrayList<Tok>();
            for (var t : toks) {
                var s = t.toString();
                if (s.endsWith("...") && s.length() > 3) {
                    ntoks.add(new Tok(t.l, t.k, t.ln, t.start, t.end - 3));
                    ntoks.add(new Tok(t.l, Kind.DELIMITER, t.ln, t.end - 3, t.end));
                } else {
                    ntoks.add(t);
                }
            }
            return ntoks;
        }

        List<Tok> combineTokens(List<Tok> toks) {
            for (int i = 1; i < toks.size(); i++) {
                var prv = toks.get(i - 1);
                var cur = toks.get(i);
                if (matchSymbol(prv, cur, "::")
                        || matchSymbol(prv, cur, ">:")
                        || matchSymbol(prv, cur, "<<")
                        || matchSymbol(prv, cur, "==")
                        || matchSymbol(prv, cur, "===")
                        || matchSymbol(prv, cur, "<:")) {
                    toks.set(i - 1, null);
                    toks.set(i, new Tok(this, Kind.DELIMITER, prv.ln, prv.start, cur.end));
                } else if (smashIdentString(prv, cur)) {
                    toks.set(i - 1, null);
                    toks.set(i, new Tok(this, Kind.IDENTIFIER, prv.ln, prv.start, cur.end));
                    // This combines an identifier and a string that must be adjenct
                } else if (smashColumnIdent(prv, cur)) {
                    toks.set(i - 1, null);
                    toks.set(i, new Tok(this, Kind.IDENTIFIER, prv.ln, prv.start, cur.end));
                    // This combines an identifier and a string that must be adjenct
                }
            }
            return toks.stream().filter(t -> t != null).collect(Collectors.toList());
        }

        boolean smashIdentString(Tok prv, Tok cur) {
            var adjacent = prv.end == cur.start;
            var combination = prv.k == Kind.IDENTIFIER && cur.k == Kind.STRING;
            return adjacent && combination;
        }

        boolean smashColumnIdent(Tok prv, Tok cur) {
            var adjacent = prv.end == cur.start;
            var combination = prv.k == Kind.DELIMITER && cur.k == Kind.IDENTIFIER && prv.toString().equals(":");
            return adjacent && combination;
        }

        boolean matchSymbol(Tok prv, Tok cur, String sym) {
            var adjacent = prv.end == cur.start;
            var same = (prv.toString() + cur.toString()).equals(sym);
            return adjacent && same;
        }

        // We should probably cache the string value, but ... meh
        record Tok(Lex l, Kind k, int ln, int start, int end) {

            @Override
            public String toString() {
                return isEOF() ? "EOF" : l.lns[ln].substring(start, end);
            }

            String errorAt(String msg) {
                return "\n> " + l.lns[ln] + "\n> " + " ".repeat(start)
                        + CodeColors.standout("^----" + msg + " at line " + ln);
            }

            String getLine() {
                return l.lns[ln];
            }

            boolean is(String s) {
                return toString().equals(s);
            }

            static Pattern pattern = Pattern.compile("[-+]?\\d*\\.?\\d+([eE][-+]?\\d+)?");

            boolean isNumber() {
                return pattern.matcher(toString()).matches();
            }

            boolean isEOF() {
                return k == Kind.EOF;
            }

        }

        boolean isDelimiter(int c) {
            for (char d : delimiters) {
                if (c == d) {
                    return true;
                }
            }
            return false;
        }

        Tok next() {
            if (pos >= lns.length) {
                return new Tok(this, Kind.EOF, pos, off, off);
            }
            if (off >= lns[pos].length()) {
                pos++;
                off = 0;
                return next();
            }
            int cp = lns[pos].codePointAt(off);
            var start = off;
            if (Character.isWhitespace(cp)) {
                off++;
                while (off < lns[pos].length() && Character.isWhitespace(lns[pos].codePointAt(off))) {
                    off++;
                }
                return next();
            } else if (isDelimiter(cp)) {
                off++;
                return new Tok(this, Kind.DELIMITER, pos, start, off);
            } else if (cp == '"') {
                off++;
                while (off < lns[pos].length() && lns[pos].charAt(off) != '"') {
                    off++;
                }
                off++;
                return new Tok(this, Kind.STRING, pos, start, off);
            } else if (cp == '\'') {
                off++;
                while (off < lns[pos].length() && lns[pos].charAt(off) != '\'') {
                    off++;
                }
                off++;
                return new Tok(this, Kind.STRING, pos, start, off);
            } else {
                off++;
                while (off < lns[pos].length()
                        && !Character.isWhitespace(lns[pos].codePointAt(off))
                        && !isDelimiter(lns[pos].codePointAt(off))
                        && lns[pos].charAt(off) != '"'
                        && lns[pos].charAt(off) != '\'') {
                    off++;
                }
                return new Tok(this, Kind.IDENTIFIER, pos, start, off);
            }
        }
    }

    // Yields a new parser for the slice of tokens between s and its matching e.
    // Used for "(" and ")" and "{" and "}".
    Parser sliceMatchedDelims(String s, String e) {
        var p = new Parser();
        if (!has(s)) {
            return p; // empty slice
        }
        drop();
        var count = 1; // we have seen 1 starting delimiter
        while (!(count == 1 && has(e))) { // stop when next token is matching close
            var tok = take();
            p.add(tok);
            if (tok.is(s)) {
                count++;
            } else if (tok.is(e)) {
                count--;
            } else if (tok.isEOF()) {
                failAt("Missing '" + e + "'", last);
            }
        }
        return p; // leading s and trailing e not included
    }

    Parser sliceNextCommaOrSemi() {
        var p = new Parser();
        var count = 0;
        while (!isEmpty()) {
            var tok = take();
            p.add(tok);
            if (tok.is("(") || tok.is("{")) {
                count++;
            } else if (tok.is(")") || tok.is("}")) {
                count--;
            }
            if (count == 0 && (has(",") || has(";"))) {
                break;
            }
        }
        if (has(",") || has(";")) {
            drop(); // don't drop if empty
        }
        if (count != 0) {
            failAt("Bracketing went wrong while looking for  , or ;", last);
        }
        return p;
    }

    Parser sliceLine() {
        var p = new Parser();
        if (isEmpty()) {
            return p;
        }
        int ln = peek().ln;
        while (peek().ln == ln) {
            p.add(take());
        }
        return p;
    }

    boolean isEmpty() {
        return toks.isEmpty();
    }

    boolean has(String s) {
        return peek().is(s);
    }

    Lex.Tok eof() {
        return new Lex.Tok(null, Kind.EOF, 0, 0, 0);
    }

    Lex.Tok peek() {
        var tok = !toks.isEmpty() ? toks.get(0) : eof();
        if (!tok.isEOF()) {
            last = tok;
        }
        return tok;
    }

    Parser drop() {
        toks.remove(0);
        return this;
    }

    Lex.Tok take() {
        return !toks.isEmpty() ? toks.remove(0) : eof();
    }

    Parser add(Lex.Tok t) {
        toks.addLast(t);
        return this;
    }

    void failAt(String msg, Lex.Tok last) {
        throw new RuntimeException(last == null ? "Huh?" : last.errorAt(msg));
    }

    void failIfEmpty(String msg, Lex.Tok last) {
        if (isEmpty()) {
            failAt(msg, last);
        }
    }
}

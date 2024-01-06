package prlprg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import static prlprg.CodeColors.color;

class Parser {

    interface ParsedType {

        // Creates the simpler format
        Ty toTy();

        // ParsedType names can include string and numbers and concatenated vars
        static String parseTypeName(Parser p) {
            var tok = p.take();
            if (tok.is("typeof") || tok.is("keytype")) {
                var str = tok.toString();
                var q = p.sliceMatchedDelims("(", ")");
                if (q.isEmpty()) {
                    return str; // typeof or keytype without parens (odd?)
                }
                str += "(";
                while (!q.isEmpty()) {
                    str += q.take().toString();
                }
                return str + ")";
            } else {
                var nm = tok.toString();
                if (nm.startsWith("\"") && nm.endsWith("\"")) {
                    return nm; // A quoted string, leave it as is.
                } else {
                    return nm.replaceAll("\"", ""); // Get rid of quotes in case of MIME"xyz"
                }
            }
        }

        // Function names are dotted identifiers and delims.
        static String parseFunctionName(Parser p) {
            var str = "";
            while (!p.isEmpty() && !p.peek().is("(")) {
                str += p.take().toString();
            }
            if (str.isEmpty()) {
                p.failAt("Missing function name", p.peek());
            }
            return str.replaceAll("\"", "");  // Get rid of quotes in case of "fun"
        }

    }

// An instance of a datatype constructor (not a union all or bound var).
    record TypeInst(String nm, List<ParsedType> ps) implements ParsedType {

        static ParsedType parse(Parser p) {
            var name = ParsedType.parseTypeName(p);
            List<ParsedType> params = new ArrayList<>();
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
        public Ty toTy() {
            if (ps.isEmpty() && (nm.equals("true") || nm.equals("false") || nm.startsWith(":")
                    || nm.startsWith("\"") || nm.startsWith("\'")
                    || nm().startsWith("typeof(") || pattern.matcher(nm).matches())) {
                return new TyCon(nm);
            }
            if (nm.equals("nothing")) {
                return Ty.none();
            }
            var tys = ps.stream().map(tt -> tt.toTy()).collect(Collectors.toList());
            return nm.equals("Tuple") ? new TyTuple(tys)
                    : nm.equals("Union") ? new TyUnion(tys)
                    : new TyInst(nm, tys);
        }

        @Override
        public String toString() {
            return nm + (ps.isEmpty() ? ""
                    : "{" + ps.stream().map(Object::toString).collect(Collectors.joining(", ")) + "}");
        }
    }

// A variable with optional upper and lower bounds. At this point we are still a little shaky
// on which is which, so we parse all of them to ParsedType. Also, if we get an implicit variable
// name, we create a fesh name for it. thwse names start with a question mark.
    record BoundVar(ParsedType name, ParsedType lower, ParsedType upper) implements ParsedType {

        static TypeInst fresh() {
            var nm = NameUtils.fresh();
            return new TypeInst(nm, new ArrayList<>());
        }

        // can get ::  T   |   T <: U   |   L <: T <: U   | T >: L |   >: L
        static ParsedType parse(Parser p) {
            if (p.has("<:") || p.has(">:")) {
                var lt = p.has("<:");
                var t = UnionAllInst.parse(p.drop());
                return lt ? new BoundVar(fresh(), null, t) : new BoundVar(fresh(), t, null);
            }
            var t = UnionAllInst.parse(p);
            if (p.has("<:")) {
                var u = UnionAllInst.parse(p.drop());
                return p.has("<:")
                        ? new BoundVar(UnionAllInst.parse(p.drop()), t, u)
                        : new BoundVar(t, null, u);
            } else if (p.has(">:")) {
                return p.isEmpty() ? new BoundVar(fresh(), t, null)
                        : new BoundVar(t, UnionAllInst.parse(p.drop()), null);
            } else {
                return t;
            }
        }

        // Create a bound variable, the default for lower and upper bouds are none (Union{}) and Any.
        @Override
        public Ty toTy() {
            return new TyVar(name.toString(), lower == null ? Ty.none() : lower.toTy(),
                    (upper == null || upper.toString().equals("Any")) ? Ty.any() : upper.toTy());
        }

        @Override
        public String toString() {
            return (lower != null ? lower + " <: " : "") + name.toString() + (upper != null ? " <: " + upper : "");
        }
    }

// Int{X} where {Z <: X <: Y, Y <: Int} where {X <: Int}
    record UnionAllInst(ParsedType body, List<ParsedType> bounds) implements ParsedType {

        static ParsedType parse(Parser p) {
            //  U<:(AbstractVector)   the input appears occasionally to have extraneous parens.
            if (p.has("(")) { // this should get rid of them.
                p = p.sliceMatchedDelims("(", ")");
                if (p.isEmpty()) {
                    // This is a special case the whole type was '()' which apparently is an empty Tuple.
                    // But it is not a type... In our examples it ocrurs in the following context:
                    //    NamedTuple{()}
                    // not sure what to do. So return nothing.
                    return new TypeInst("Nothing", new ArrayList<>());
                }
            }
            var type = TypeInst.parse(p);
            if (!p.has("where")) {
                return type;
            }
            if (p.drop().has("{")) {
                p = p.sliceMatchedDelims("{", "}");
            }
            var boundVars = new ArrayList<ParsedType>();
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
        public Ty toTy() {
            var ty = body.toTy();
            var it = bounds.listIterator(bounds.size());
            while (it.hasPrevious()) { // Going backwards, building Exists inside out
                ty = new TyExist(it.previous().toTy(), ty);
            }
            return ty;
        }

        @Override
        public String toString() {
            return body.toString() + (bounds.isEmpty() ? ""
                    : " where " + bounds.stream().map(Object::toString).collect(Collectors.joining(", ")));
        }
    }

    record TypeDeclaration(String modifiers, String nm, List<ParsedType> ps, ParsedType parent, String src) {

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
            NameUtils.reset();
            var modifiers = parseModifiers(p);
            var name = ParsedType.parseTypeName(p);
            var src = p.last.getLine();
            var ps = new ArrayList<ParsedType>();
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
            ParsedType parent = null;
            if (p.has("<:")) {
                p.drop();
                parent = UnionAllInst.parse(p);
            }
            p.drop();
            p.sliceMatchedDelims("(", ")");
            return new TypeDeclaration(modifiers, name, ps, parent, src);
        }

        TyDecl toTy() {
            var parentTy = (parent == null || parent.toString().equals("Any")) ? Ty.any() : parent.toTy();
            var args = ps.stream().map(tt -> tt.toTy()).collect(Collectors.toList());
            var ty = new TyInst(nm, args);
            return new TyDecl(modifiers, nm, ty, parentTy, src);
        }

        @Override
        public String toString() {
            return modifiers + nm
                    + (ps.isEmpty() ? "" : ("{" + ps.stream().map(Object::toString).collect(Collectors.joining(", ")) + "}"))
                    + (parent == null ? "" : (" <: " + parent));
        }
    }

    record Param(String nm, ParsedType ty, boolean varargs) {

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

        Ty toTy() {
            return ty == null ? Ty.any() : ty.toTy();
        }

        @Override
        public String toString() {
            return nm + (ty != null ? " :: " + ty.toString() : "") + (varargs ? "..." : "");
        }
    }

    record Function(String nm, List<Param> ps, List<ParsedType> wheres, String src) {

        static List<ParsedType> parseWhere(Parser p) {
            var wheres = new ArrayList<ParsedType>();
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
                } else if (p.has("[")) { // comment with line/file info
                    break;
                }
            }
            return wheres;
        }

        static Function parse(Parser p) {
            NameUtils.reset();
            if (p.has("function")) {  // keyword is optional
                p.drop();
            }
            var name = ParsedType.parseFunctionName(p);
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
            if (p.has("[")) {
                p.sliceMatchedDelims("[", "]"); // drops it
            }
            return new Function(name, params, wheres, source);
        }

        TySig toTy() {
            List<Ty> tys = ps.stream().map(Param::toTy).collect(Collectors.toList());
            var reverse = wheres.reversed();
            Ty nty = new TyTuple(tys);
            for (var where : reverse) {
                nty = new TyExist(where.toTy(), nty);
            }
            return new TySig(nm, nty, src);
        }

        @Override
        public String toString() {
            return nm + "(" + ps.stream().map(Object::toString).collect(Collectors.joining(", ")) + ")"
                    + (wheres.isEmpty() ? ""
                    : (" where " + wheres.stream().map(Object::toString).collect(Collectors.joining(", "))));
        }
    }

    private List<Lex.Tok> toks = new ArrayList<>();
    Lex.Tok last;

    // When reading data from file, there may be closures, we usually don't want to look at those types/signatures
    Parser withFile(String path) {
        try {
            List<String> ls = Files.readAllLines(Path.of(path));
            toks = new Lex(ls.toArray(new String[0])).tokenize();
            return this;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    Parser withString(String s) {
        toks = new Lex(s.split("\n")).tokenize();
        return this;
    }

    void parseSigs() {
        while (!isEmpty()) {
            GenDB.addSig(Function.parse(sliceLine()).toTy());
        }
    }

    void parseTypes() {
        while (!isEmpty()) {
            GenDB.types.addParsed(TypeDeclaration.parse(sliceLine()));
        }
    }

    enum Kind {
        DELIMITER, IDENTIFIER, STRING, EOF;
    }

    class Lex {

        String[] lns;
        int pos, off;
        static char[] delimiters = {'{', '}', ':', ',', ';', '=', '(', ')', '[', ']', '#', '<', '>'};

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

        // Our parser is a bit dumb, we need to path the token stream, combining some adjacent
        // tokens to form the right one. This is a bit of a hack, but it works.
        List<Tok> combineTokens(List<Tok> toks) {
            var smashed = false;
            for (int i = 1; i < toks.size(); i++) {
                var prv = toks.get(i - 1);
                var cur = toks.get(i);
                var dd = prv.isDelimiter() && cur.isDelimiter();
                var is = prv.isIdentifier() && cur.isString();
                var di = prv.isDelimiter() && cur.isIdentifier();
                if (!dd && !is && !di) {
                    continue;
                }
                if (matchSymbol(prv, cur, "::")
                        || matchSymbol(prv, cur, ">:")
                        || matchSymbol(prv, cur, "<<")
                        || matchSymbol(prv, cur, "==")
                        || matchSymbol(prv, cur, "===")
                        || matchSymbol(prv, cur, "<:")) {
                    toks.set(i - 1, null);
                    toks.set(i, new Tok(this, Kind.DELIMITER, prv.ln, prv.start, cur.end));
                    smashed = true;
                } else if (smashIdentString(prv, cur)) {
                    toks.set(i - 1, null);
                    toks.set(i, new Tok(this, Kind.IDENTIFIER, prv.ln, prv.start, cur.end));
                    // This combines an identifier and a string that must be adjacent
                    smashed = true;
                } else if (smashColumnIdent(prv, cur)) {
                    toks.set(i - 1, null);
                    toks.set(i, new Tok(this, Kind.IDENTIFIER, prv.ln, prv.start, cur.end));
                    // This combines an identifier and a string that must be adjenct
                    smashed = true;
                }
            }
            return smashed ? toks.stream().filter(t -> t != null).collect(Collectors.toList()) : toks;
        }

        boolean smashIdentString(Tok prv, Tok cur) {
            var adjacent = prv.end == cur.start;
            var combination = prv.isIdentifier() && cur.isString();
            return adjacent && combination;
        }

        boolean smashColumnIdent(Tok prv, Tok cur) {
            var adjacent = prv.end == cur.start;
            var prvIsCol = prv.length() == 1 && prv.charAt(prv.start) == ':';
            return adjacent && prvIsCol && prv.isDelimiter() && cur.isIdentifier();
        }

        boolean matchSymbol(Tok prv, Tok cur, String sym) {
            if (prv.end != cur.start) { // not adjacent, can't match is space separated
                return false;
            }
            if (cur.end - prv.start != sym.length()) { // not the right length
                return false;
            }
            for (int i = 0; i < sym.length(); i++) {
                if (sym.charAt(i) != prv.charAt(i + prv.start)) { // can read past of token,
                    return false; // as long as both are on the same line, which they are
                }
            }
            return true;
        }

        // We should probably cache the string value, but ... meh
        record Tok(Lex l, Kind k, int ln, int start, int end) {

            @Override
            public String toString() {
                return isEOF() ? "EOF" : getLine().substring(start, end);
            }

            String errorAt(String msg) {
                return "\n> " + getLine() + "\n> " + " ".repeat(start) + color("^----" + msg + " at line " + ln, "Red");
            }

            String getLine() {
                return l.lns[ln];
            }

            // can read past the end of the token, but not past the end of the line.
            char charAt(int i) {
                return getLine().charAt(i);
            }

            // Check that this token is the given string.
            boolean is(final String s) {
                if (length() != s.length()) { // have to be of the same length
                    return false;
                }
                for (int i = 0; i < s.length(); i++) {
                    if (charAt(i + start) != s.charAt(i)) {
                        return false;
                    }
                }
                return true;
            }

            int length() {
                return end - start;
            }

            // Check that this token is plausibly a number. It can have a single dot, and no sign/or E.
            boolean isNumber() {
                var dots = 0;
                for (int i = start; i < end; i++) {
                    var c = charAt(i);
                    var digit = Character.isDigit(c);
                    if (!digit || c != '.') {
                        return false;
                    }
                    if (!digit) {
                        dots++;
                    }
                }
                return dots == 0 || (dots == 1 && length() > 1);
            }

            boolean isEOF() {
                return k == Kind.EOF;
            }

            boolean isDelimiter() {
                return k == Kind.DELIMITER;
            }

            boolean isIdentifier() {
                return k == Kind.IDENTIFIER;
            }

            boolean isString() {
                return k == Kind.STRING;
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
        drop();
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
        while (!peek().isEOF() && peek().ln == ln) {
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

/**
 * An early form of Julia Type coming from the Parser.
 *
 * @author jan
 */
interface Ty {

    final static Ty Any = new TyInst("Any", List.of());
    final static Ty None = new TyUnion(List.of());

    /**
     * @return the Any Ty
     */
    static Ty any() {
        return Any;
    }

    /**
     * @return the None Ty, ie. Union{}
     */
    static Ty none() {
        return None;
    }

    /**
     * Transforms a Ty to a Type object, bounds are resolved from their
     * enclosing environment.
     *
     * @param env bounds in scope
     */
    Type toType(List<Bound> env);

    /**
     * The parser does not know what identifiers represent variables and what
     * represent types. The fix up method constructs a list of variables in
     * scope and replaces TyInst by TyVar when appropriate. Fixup also takes
     * care of `typedf` which is turned into a constant (TyCon). `Nothing` is an
     * alias that is replaced by Union{}. If fixup encounters an identifier that
     * is not a varaiable and that does not occur in the GendDB, it will assume
     * that this is a missing type and add it.
     *
     * @param bounds bounds in scope
     * @return a fixed up Ty
     */
    Ty fixUp(List<TyVar> bounds);

}

record TyInst(String nm, List<Ty> tys) implements Ty {

    @Override
    public String toString() {
        var args = tys.stream().map(Ty::toString).collect(Collectors.joining(","));
        return nm + (tys.isEmpty() ? "" : "{" + args + "}");
    }

    /**
     * After this call we have a valide TyInst or TyVar, or a TyCon or a
     * Ty.None.
     */
    @Override
    public Ty fixUp(List<TyVar> bounds) {
        var varOrNull = bounds.stream().filter(v -> v.nm().equals(nm())).findFirst();
        if (varOrNull.isPresent()) {
            if (tys.isEmpty()) {
                return varOrNull.get();
            }
            throw new RuntimeException("Type " + nm() + " is a variable used as a type.");
        }
        switch (nm()) {
            case "typeof": // typeof is a special case
                return new TyCon(toString());
            case "Nothing":
                return Ty.None;
            default:
                GenDB.types.patchIfNeeeded(nm);
                var args = new ArrayList<Ty>();
                var vars = new ArrayList<TyVar>();
                var newBounds = new ArrayList<TyVar>(bounds);
                for (var a : tys) {
                    if (a instanceof TyVar tv && !inList(tv, bounds)) {
                        tv = (TyVar) tv.fixUp(bounds);
                        newBounds.add(tv);
                        vars.add(tv);
                        args.add(tv);
                    } else {
                        args.add(a.fixUp(newBounds));
                    }
                }
                Ty t = new TyInst(nm, args);
                for (var v : vars) {
                    t = new TyExist(v, t);
                }
                return t;
        }
    }

    /**
     * @return true if the given var is in the list of bounds.
     */
    private static boolean inList(TyVar tv, List<TyVar> bounds) {
        return bounds.stream().anyMatch(b -> b.nm().equals(tv.nm()));
    }

    /**
     * The TyInst becomes an Inst with all parameters recurisvely converted.
     */
    @Override
    public Type toType(List<Bound> env) {
        return new Inst(nm, tys.stream().map(tt -> tt.toType(env)).collect(Collectors.toList()));
    }

}

record TyVar(String nm, Ty low, Ty up) implements Ty {

    @Override
    public String toString() {
        return (!low.equals(Ty.none()) ? low + "<:" : "") + CodeColors.variable(nm) + (!up.equals(Ty.any()) ? "<:" + up : "");
    }

    /**
     * A Var has a reference to its defining Bound, in the process of converting
     * a Ty to a Type we need to have the bounds in scope so we can initialize
     * the Var with the correct bounds.
     */
    @Override
    public Type toType(List<Bound> env) {
        var vne_stream = env.reversed().stream();
        var maybe = vne_stream.filter(b -> b.nm().equals(nm)).findFirst();
        if (maybe.isPresent()) {
            return new Var(maybe.get());
        }
        throw new RuntimeException("Variable " + nm + " not found in environment");
    }

    /**
     * Recursively fix up the bounds.
     */
    @Override
    public Ty fixUp(List<TyVar> bounds) {
        return new TyVar(nm, low.fixUp(bounds), up.fixUp(bounds));
    }

}

/**
 * A constant such as 5 or a symbol or a typeof. The value of the constant is
 * kept as its original string representation.
 */
record TyCon(String nm) implements Ty {

    @Override
    public String toString() {
        return nm;
    }

    @Override
    public Type toType(List<Bound> env) {
        return new Con(nm);
    }

    @Override
    public Ty fixUp(List<TyVar> bounds) {
        return this;
    }
}

record TyTuple(List<Ty> tys) implements Ty {

    @Override
    public String toString() {
        return "(" + tys.stream().map(Ty::toString).collect(Collectors.joining(",")) + ")";
    }

    @Override
    public Type toType(List<Bound> env) {
        return new Tuple(tys.stream().map(tt -> tt.toType(env)).collect(Collectors.toList()));
    }

    @Override
    public Ty fixUp(List<TyVar> bounds) {
        return new TyTuple(tys.stream().map(t -> t.fixUp(bounds)).collect(Collectors.toList()));
    }

}

record TyUnion(List<Ty> tys) implements Ty {

    @Override
    public String toString() {
        return "[" + tys.stream().map(Ty::toString).collect(Collectors.joining("|")) + "]";
    }

    @Override
    public Type toType(List<Bound> env) {
        return new Union(tys.stream().map(tt -> tt.toType(env)).collect(Collectors.toList()));
    }

    @Override
    public Ty fixUp(List<TyVar> bounds) {
        return new TyUnion(tys.stream().map(t -> t.fixUp(bounds)).collect(Collectors.toList()));
    }

}

record TyExist(Ty v, Ty ty) implements Ty {

    @Override
    public String toString() {
        return CodeColors.exists("∃") + v + CodeColors.exists(".") + ty;
    }

    /**
     * The TyExist becomes an Exist with all parameters recurisvely converted. Fix up needs to patch the 
     * parser's work which may have created a TyInst for the bound variable.
     */
    @Override
    public Ty fixUp(List<TyVar> bound) {
        var maybeVar = v();
        var body = ty();
        if (maybeVar instanceof TyVar defVar) {
            var tv = new TyVar(defVar.nm(), defVar.low().fixUp(bound), defVar.up().fixUp(bound));
            var newBound = new ArrayList<>(bound);
            newBound.add(tv);
            return new TyExist(tv, body.fixUp(newBound));
        } else if (maybeVar instanceof TyInst inst) {
            var tv = new TyVar(inst.nm(), Ty.none(), Ty.any());
            var newBound = new ArrayList<>(bound);
            newBound.add(tv);
            return new TyExist(tv, body.fixUp(newBound));
        } else if (maybeVar instanceof TyTuple) {
            // struct RAICode.QueryEvaluator.Vectorized.Operators.var\"#21#22\"{var\"#10063#T\", var\"#10064#vars\", var_types, *, var\"#10065#target\", Tuple} <: Function end (from module RAICode.QueryEvaluator.Vectorized.Operators)
            throw new RuntimeException("Should be a TyVar or a TyInst with no arguments: got tuple ");
        } else {
            throw new RuntimeException("Should be a TyVar or a TyInst with no arguments: " + maybeVar);
        }
    }

    @Override
    public Type toType(List<Bound> env) {
        String name;
        Type low;
        Type up;
        if (v() instanceof TyVar tvar) {
            name = tvar.nm();
            low = tvar.low().toType(env);
            up = tvar.up().toType(env);
        } else { // Given that we have alreadty performed fixup, this should not occur. Or? 
            var inst = (TyInst) v();
            if (!inst.tys().isEmpty()) {
                throw new RuntimeException("Should be a TyVar but is a type: " + ty);
            }
            name = inst.nm();
            low = GenDB.none;
            up = GenDB.any;
        }
        var b = new Bound(name, low, up);
        var newenv = new ArrayList<>(env);
        newenv.add(b);
        return new Exist(b, ty().toType(newenv));
    }

}

/**
 * A type declaration has modifiers, a name, type expression and a parent type. We also keep the source text
 * for debugging purposes.
 */
record TyDecl(String mod, String nm, Ty ty, Ty parent, String src) {

    @Override
    public String toString() {
        return nm + " = " + mod + " " + ty + " <: " + parent + CodeColors.comment("\n# " + src);
    }

    /**
     * Given a type declaration,
     * <pre>
     *   mod lhs <: rhs end </pre>
     * where lhs has the form T{X} and T is a type name, X is a variable, 
     * and rhs has the form S{Ty} where S is a type name and Ty is a Ty expression.
     * This method fixes up X and rhs. It also wraps LHS into TyExists.
     * 
     * @return a new TyDecl with all elements recurisvely fixed up
     */
    TyDecl fixUp() {
        var lhs = (TyInst) ty;
        var rhs = (TyInst) parent;
        var fixedArgs = new ArrayList<TyVar>();
        for (var targ : lhs.tys()) {
            switch (targ) {
                case TyVar tv ->
                    fixedArgs.add((TyVar) tv.fixUp(fixedArgs));
                case TyInst ti -> {
                    if (!ti.tys().isEmpty()) {
                        throw new RuntimeException("Should be a TyVar but is a type: " + targ);
                    }
                    fixedArgs.add(new TyVar(ti.nm(), Ty.none(), Ty.any()));
                }
                default ->
                    throw new RuntimeException("Should be a TyVar or a TyInst with no arguments: " + targ);
            }
        }
        var fixedRHS = (TyInst) rhs.fixUp(fixedArgs);
        var args = new ArrayList<Ty>();
        args.addAll(fixedArgs);
        Ty t = new TyInst(lhs.nm(), args);
        for (var arg : fixedArgs.reversed()) {
            t = new TyExist(arg, t);
        }
        return new TyDecl(mod, nm, t, fixedRHS, src);
    }

}

/**
 * The representation of a method with a name and type signature (a tuple possibly wrapped in existentials).
 * The source information is retained for debugging purposes.
 */
record TySig(String nm, Ty ty, String src) {

    @Override
    public String toString() {
        return "function " + nm + " " + ty + CodeColors.comment("\n# " + src);

    }

    /**
     * Fixes up the signatures of the method.
     */
    TySig fixUp(List<TyVar> bounds) {
        return new TySig(nm, ty.fixUp(bounds), src);
    }

}

package prlprg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

abstract class Type {

    // Creates the simpler format
    abstract GenDB.Ty toTy();
}

// Numbers can appear in a type signature
class DependentType extends Type {

    String value;

    DependentType(String value) {
        this.value = value;
    }

    // Creates the simpler format
    @Override
    GenDB.Ty toTy() {
        return new GenDB.TyCon(value);
    }

    static Type parse(Parser p) {
        var tok = p.peek();
        if (!tok.isNumber()) {
            return null;
        }
        p.advance();
        return new DependentType(tok.toString());
    }

}

// An instance of a datatype constructor (not a union all or bound)
class TypeInst extends Type {

    TypeName name;
    List<Type> typeParams;

    TypeInst(TypeName name, List<Type> typeParams) {
        this.name = name;
        this.typeParams = typeParams;
    }

    static Type parse(Parser p) {
        var dep = DependentType.parse(p);
        if (dep != null) // If we see a number, it's a dependent type
        {
            return dep;
        }
        var name = TypeName.parse(p);
        var params = new ArrayList<Type>();
        var tok = p.peek();
        if (tok.isString()) { // Covers the case of MIME"xyz" which is a type
            var str = tok.toString().replaceAll("\"", "");
            name = new TypeName(name.toString() + str);
            p.advance();
        }
        // Hack, Val{:el} is a tyoe
        if (name.equals("Val")) {
            var str = "";
            if (p.peek().delim("{")) {
                p.advance();
                while (!p.peek().delim("}")) {
                    str += p.next().toString();
                }
                p.advance();
            }
            params.add(new DependentType(str));
            return new TypeInst(name, params);
        } else if (tok.delim("{")) {
            tok = p.advance().peek();
            while (!tok.delim("}")) {
                if (tok.delim(",")) {
                    tok = p.advance().peek();
                    continue;
                } else {
                    params.add(BoundVar.parse(p));
                }
                tok = p.peek();
            }
            p.next();
        }
        return new TypeInst(name, params);
    }

    // An instance of a datatype with zero or more type parameters.
    // In the case of Union and Tuple types, create the specific types.
    @Override
    GenDB.Ty toTy() {
        var ps = typeParams.stream().map(tt -> tt.toTy()).collect(Collectors.toList());
        return name.equals("Tuple") ? new GenDB.TyTuple(ps)
                : name.equals("Union") ? new GenDB.TyUnion(ps)
                : new GenDB.TyInst(name.name(), ps);
    }

    @Override
    public String toString() {
        var str = name.toString();
        if (typeParams != null && !typeParams.isEmpty()) {
            str += "{";
            for (int i = 0; i < typeParams.size(); i++) {
                str += typeParams.get(i).toString();
                if (i < typeParams.size() - 1) {
                    str += ", ";
                }
            }
            str += "}";
        }
        return str;
    }
}

class BoundVar extends Type {

    Type name;
    Type lower;
    Type upper;

    BoundVar(Type name, Type lower, Type upper) {
        this.name = name;
        this.lower = lower;
        this.upper = upper;
    }

    static boolean gotParen(Parser p) {
        if (p.peek().delim("(")) {
            p.advance();
            return true;
        }
        return false;
    }

    static void getParen(Parser p, boolean gotParen) {
        if (gotParen) {
            if (p.peek().delim(")")) {
                p.advance();
            } else {
                p.failAt("Missing closing paren", p.peek());
            }
        }
    }

    static int gen = 0;

    static Type parse(Parser p) {
        if (p.peek().delim("<:")) {
            boolean gotParen = gotParen(p.advance());
            var fresh = new TypeInst(new TypeName("?" + ++gen), null);
            var b = new BoundVar(fresh, null, UnionAllInst.parse(p));
            getParen(p, gotParen);
            return b;
        } else {
            var type = UnionAllInst.parse(p);
            if (p.peek().delim("<:")) {
                boolean gotParen = gotParen(p.advance());
                var upper = UnionAllInst.parse(p);
                getParen(p, gotParen);
                Type lower = null;
                if (p.peek().delim("<:")) {
                    gotParen = gotParen(p.advance());
                    lower = UnionAllInst.parse(p);
                    getParen(p, gotParen);
                }
                return new BoundVar(type, lower, upper);
            } else if (p.peek().delim(">:")) {
                return new BoundVar(type, UnionAllInst.parse(p.advance()), null);
            } else {
                return type;
            }
        }
    }

    // Create a bound variable, the default for lower and upper bouds are none (Union{}) and Any.
    @Override
    GenDB.Ty toTy() {
        return new GenDB.TyVar(name.toString(), lower == null ? GenDB.Ty.none() : lower.toTy(),
                (upper == null || upper.toString().equals("Any")) ? GenDB.Ty.any() : upper.toTy());
    }

    @Override
    public String toString() {
        return (lower != null ? lower + " <: " : "") + name.toString() + (upper != null ? " <: " + upper : "");
    }
}

// Int{X} where {Z <: X <: Y, Y <: Int} where {X <: Int}
class UnionAllInst extends Type {

    Type type;
    List<Type> boundVars;

    UnionAllInst(Type type, List<Type> boundVars) {
        this.type = type;
        this.boundVars = boundVars;
    }

    static Type parse(Parser p) {
        var type = TypeInst.parse(p);
        var tok = p.peek();
        if (tok.ident("where")) {
            p.advance();
            var boundVars = new ArrayList<Type>();
            var gotBrace = false;
            if (p.peek().delim("{")) {
                p.advance();
                gotBrace = true;
            }
            boundVars.add(BoundVar.parse(p));
            while (gotBrace && p.peek().delim(",")) {
                boundVars.add(BoundVar.parse(p.advance()));
            }
            if (gotBrace) {
                if (p.peek().delim("}")) {
                    p.advance();
                } else {
                    p.failAt("Missing closing brace", p.peek());
                }
            }
            return new UnionAllInst(type, boundVars);
        } else {
            return type;
        }
    }

    @Override
    GenDB.Ty toTy() {
        var ty = type.toTy();
        var it = boundVars.listIterator(boundVars.size());
        while (it.hasPrevious()) {
            var boundVar = it.previous().toTy();
            ty = new GenDB.TyExist(boundVar, ty);
        }
        return ty;
    }

    @Override
    public String toString() {
        var str = type.toString();
        if (boundVars != null && !boundVars.isEmpty()) {
            str += " where ";
            for (int i = 0; i < boundVars.size(); i++) {
                str += boundVars.get(i).toString();
                if (i < boundVars.size() - 1) {
                    str += ", ";
                }
            }
        }
        return str;
    }
}

class TypeDeclaration {

    String modifiers;
    TypeName name;
    List<Type> typeParams;
    Type parent;
    String sourceLine;

    TypeDeclaration(String modifiers, TypeName name, List<Type> typeParams, Type parent, String source) {
        this.modifiers = modifiers;
        this.name = name;
        this.typeParams = typeParams;
        this.parent = parent;
        this.sourceLine = source;

    }

    static String maybeStruct(Parser p) {
        var str = "";
        if (p.peek().delim("(")) {
            if (p.advance().next().ident("closure") && p.next().delim(")")) {
                str += "(closure) ";
                p.advance();
            } else {
                p.failAt("Invalid type declaration, expected (closure) ", p.peek());
            }
        }
        if (p.peek().ident("mutable")) {
            str += "mutable ";
            p.advance();
        }
        if (p.peek().ident("struct")) {
            str += "struct ";
            p.advance();
        }
        return str;
    }

    static String parseModifiers(Parser p) {
        var str = maybeStruct(p);
        if (!str.equals("")) {
            return str;
        }
        var tok = p.next();
        if (tok.ident("abstract") || tok.ident("primitive")) {
            str += tok.toString() + " type ";
            tok = p.next();
            if (!tok.ident("type")) {
                p.failAt("Invalid type declaration: missing type", tok);
            }
            return str;
        }
        p.failAt("Invalid type declaration", tok);
        return null;
    }

    static boolean parseEnd(Parser p) {
        var tok = p.peek();
        if (tok.ident("end")) {
            p.advance();
            if (p.peek().delim("(")) { // Skip source information
                while (!p.peek().isEOF() && !p.peek().delim(")")) {
                    p.advance();
                }
                p.advance();
            }
            return true;
        } else {
            return false;
        }
    }

    static TypeDeclaration parse(Parser p) {
        var modifiers = parseModifiers(p);
        var name = TypeName.parse(p);
        var sourceLine = p.getLineAt(p.peek());
        var typeParams = new ArrayList<Type>();
        var tok = p.peek();
        if (tok.delim("{")) {
            tok = p.advance().peek();
            while (!tok.delim("}")) {
                if (tok.delim(",")) {
                    tok = p.advance().peek();
                    continue;
                } else {
                    typeParams.add(BoundVar.parse(p));
                }
                tok = p.peek();
            }
            p.advance(); // skip '}'
        }
        tok = p.peek();
        if (tok.isNumber()) {
            tok = p.advance().peek();
        }
        if (parseEnd(p)) {
            return new TypeDeclaration(modifiers, name, typeParams, null, sourceLine);
        } else if (tok.delim("<:")) {
            var parent = TypeInst.parse(p.advance());
            tok = p.peek();
            if (tok.isNumber()) { // skip primitive type number of bits
                tok = p.advance().peek();
            }
            if (!parseEnd(p)) {
                p.failAt("Missed end of declaration", tok);
            }
            return new TypeDeclaration(modifiers, name, typeParams, parent, sourceLine);
        }
        p.failAt("Invalid type declaration", tok);
        return null; // not reached
    }

    GenDB.TyDecl toTy() {
        var parentTy = (parent == null || parent.toString().equals("Any")) ? GenDB.Ty.any() : parent.toTy();
        var args = typeParams.stream().map(tt -> tt.toTy()).collect(Collectors.toList());
        var ty = new GenDB.TyInst(name.name(), args);
        var mod = modifiers.contains("struct") ? "struct" : "type";
        return new GenDB.TyDecl(mod, name.toString(), ty, parentTy, sourceLine);
    }

    @Override
    public String toString() {
        var str = modifiers + name.toString();
        if (typeParams != null && !typeParams.isEmpty()) {
            str += "{";
            for (int i = 0; i < typeParams.size(); i++) {
                str += typeParams.get(i).toString();
                if (i < typeParams.size() - 1) {
                    str += ", ";
                }
            }
            str += "}";
        }
        if (parent != null) {
            str += " <: " + parent.toString();
        }
        return str;
    }
}

record TypeName(String name) {

    static String readDotted(Parser p) {
        var tok = p.next();
        var str = tok.toString();
        tok = p.peek();
        while (tok.delim(".")) {
            str += "." + p.advance().nextIdentifier().toString();
            tok = p.peek();
        }
        if (p.peek().isString()) {
            str += p.next().toString();
            str = str.replaceAll("\"", "");
        }
        if (p.peek().delim(".")) {
            str += "." + p.advance().next().toString();
        }
        return str;
    }

    static TypeName parse(Parser p) {
        var tok = p.peek();
        if (tok.ident("typeof") || tok.ident("keytype")) {
            var str = tok.toString();
            p.advance();
            if (p.peek().delim("(")) {
                str += "(";
                p.advance();
                while (!p.peek().delim(")")) {
                    str += p.next().toString();
                }
                str += ")";
                p.advance();
            }
            return new TypeName(str);
        } else {
            return new TypeName(TypeName.readDotted(p));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof TypeName t) {
            return name.equals(t.name);
        } else if (o instanceof String s) {
            return name.equals(s);
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        return name;
    }
}

class FunctionName {

    String name;

    FunctionName(String name) {
        this.name = name;
    }

    static FunctionName parse(Parser p) {
        var tok = p.next();
        var str = "";

        if (tok.delim("(")) {
            tok = p.next();
            while (!tok.delim(")")) {
                str += tok.toString();
                tok = p.next();
            }
        } else {
            str = tok.toString();
            tok = p.peek();
            while (!tok.delim("(")) {
                str += p.next();
                tok = p.peek();
            }
        }
        return new FunctionName(str);
    }

    @Override
    public String toString() {
        return name;
    }
}

class Param {

    String name;
    Type type;
    String value;
    String varargs;

    Param(String name, Type type, String value, String varargs) {
        this.name = name;
        this.type = type;
        this.value = value;
        this.varargs = varargs;
    }

    static Type parseType(Parser p) {
        return p.peek().delim("::") ? UnionAllInst.parse(p.advance()) : null;
    }

    static String parseValue(Parser p) {
        return p.peek().delim("=") ? Expression.parse(p.advance()).toString() : null;
    }

    static String parseVarargs(Parser p) {
        if (!p.peek().delim("...")) {
            return null;
        }
        p.advance();
        return "...";
    }

    static Param parse(Parser p) {
        var gotParen = false;
        if (p.peek().delim("@")) {
            p.advance().advance();
            if (p.peek().delim("(")) {
                p.advance();
                gotParen = true;
            }
        }
        var name = "???";
        Type type;
        if (p.peek().isIdentifier()) {
            name = p.next().toString();
        } else if (p.peek().delim("(")) {
            name = "(";
            p.advance();
            while (!p.peek().delim(")")) {
                name += p.next().toString();
            }
            name += ")";
            p.advance();
        }
        type = Param.parseType(p);
        var value = Param.parseValue(p);
        var varargs = Param.parseVarargs(p);
        if (gotParen && !p.next().delim(")")) {
            p.failAt("Missing closing paren", p.peek());
        }
        if (name.equals("???") && type == null && value == null && varargs == null) {
            p.failAt("Invalid parameter", p.peek());
        }
        return new Param(name, type, value, varargs);
    }

    GenDB.Ty toTy() {
        return type == null ? GenDB.Ty.any() : type.toTy();
    }

    @Override
    public String toString() {
        return name
                + (type != null ? " :: " + type.toString() : "") + (value != null ? " = " + value : "")
                + (varargs != null ? "..." : "");
    }
}

class Function {

    String modifiers;
    FunctionName name;
    List<Param> typeParams = new ArrayList<>();
    List<Type> wheres = new ArrayList<>();
    String source;

    void parseModifiers(Parser p) {
        var tok = p.peek();
        var str = "";
        if (tok.delim("@")) {
            str += "@";
            tok = p.peek();
            while (!tok.isEOF() && !tok.ident("function")) {
                str += p.next().toString();
                tok = p.peek();
            }
        }
        if (tok.ident("function")) {
            p.advance();
            str += "function ";
        } else {
            p.failAt("Missied function keyword", tok);
        }
        modifiers = str;
    }

    static List<Type> parseWhere(Parser p, List<Type> wheres) {
        var tok = p.peek();
        boolean gotBrace = false;
        if (tok.delim("{")) {
            gotBrace = true;
            tok = p.advance().peek();
        }
        while (true) {
            wheres.add(BoundVar.parse(p));
            tok = p.peek();
            if (tok.delim(",")) {
                tok = p.advance().peek();
            } else {
                break;
            }
        }
        if (gotBrace) {
            if (tok.delim("}")) {
                p.advance();
            } else {
                p.failAt("Missing closing brace", tok);
            }
        }
        return wheres;
    }

    static Function parse(Parser p) {
        var f = new Function();
        f.parseModifiers(p);
        f.name = FunctionName.parse(p);
        f.source = p.getLineAt(p.peek());
        var tok = p.peek();
        if (tok.delim("(")) {
            tok = p.advance().peek();
            while (!tok.delim(")")) {
                if (tok.delim(",") || tok.delim(";")) {
                    tok = p.advance().peek();
                    continue;
                } else {
                    f.typeParams.add(Param.parse(p));
                }
                tok = p.peek();
            }
            p.advance(); // skip '}'
        }
        if (!p.peek().isEOF() && p.peek().delim("::")) {
            p.advance();
            TypeInst.parse(p); // ignore the return type
        }
        if (!p.peek().isEOF() && p.peek().ident("where")) {
            p.advance();
            parseWhere(p, f.wheres);
        }
        if (!p.peek().isEOF() && p.peek().ident("where")) {
            p.advance();
            parseWhere(p, f.wheres);
        }

        if (!p.peek().isEOF() && p.peek().delim("@")) {
            while (!p.peek().isEOF() && !p.peek().ident("function")) {
                p.advance();
            }
        }
        if (!p.peek().isEOF() && p.peek().ident("in")) {
            p.advance();
            while (!p.peek().isEOF() && !p.peek().delim(")")) {
                p.advance();
            }
            p.advance();
        }
        return f;
    }

    GenDB.TySig toTy() {
        var nm = name.toString();
        List<GenDB.Ty> tys = typeParams.stream().map(Param::toTy).collect(Collectors.toList());
        var reverse = wheres.reversed();
        GenDB.Ty ty = new GenDB.TyTuple(tys);
        for (var where : reverse) {
            ty = new GenDB.TyExist(where.toTy(), ty);
        }
        return new GenDB.TySig(nm, ty, source);
    }

    @Override
    public String toString() {
        var str = modifiers + name.toString();
        if (typeParams != null && !typeParams.isEmpty()) {
            str += "(";
            for (int i = 0; i < typeParams.size(); i++) {
                str += typeParams.get(i).toString();
                if (i < typeParams.size() - 1) {
                    str += ", ";
                }
            }
            str += ")";
        }
        if (wheres != null && !wheres.isEmpty()) {
            str += " where ";
            for (int i = 0; i < wheres.size(); i++) {
                str += wheres.get(i).toString();
                if (i < wheres.size() - 1) {
                    str += ", ";
                }
            }
        }
        return str;
    }
}

class Expression {

    String value;

    Expression(String value) {
        this.value = value;
    }

    static boolean couldBeDone(Parser.Token tok, int level) {
        boolean possibleEnd = tok.delim(",") || tok.delim(";") || tok.delim(")");
        return possibleEnd && level <= 0;
    }

    static Expression parse(Parser p) {
        int level = 0; // number of parentheses that are open
        var str = "";
        var tok = p.peek();
        while (!couldBeDone(tok, level)) {
            str += tok.toString();
            if (tok.delim("(")) {
                level++;
            } else if (tok.delim(")")) {
                level--;
            }
            tok = p.advance().peek();
        }
        return new Expression(str);
    }

    @Override
    public String toString() {
        return value;
    }
}

public class Parser {

    private String[] lines;
    private List<Token> toks;
    private Token last;
    private int curPos = 0;

    public Parser withFile(String path) {
        this.lines = getLines(path);
        return this;
    }

    public Parser withString(String s) {
        this.lines = s.split("\n");
        return this;
    }

    public void addLines(String s) {
        var ls = s.split("\n");
        var newLines = new String[lines.length + ls.length];
        System.arraycopy(lines, 0, newLines, 0, lines.length);
        System.arraycopy(ls, 0, newLines, lines.length, ls.length);
        lines = newLines;
    }

    public void tokenize() {
        toks = tokenize(lines);
    }

    private final String[] empty = new String[0];

    String[] getLines(String path) {
        try {
            Path p = Path.of(path);
            List<String> ls = Files.readAllLines(p);
            return ls.toArray(empty);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    String getLineAt(Token tok) {
        return lines[tok.line];
    }

    enum Kind {
        DELIMITER, IDENTIFIER, NUMBER, STRING, EOF;
    }

    private final Token EOF = new Token(Kind.EOF, "EOF", 0, 0);

    Token eof() {
        return EOF;
    }

    class Token {

        Kind kind;
        String val;
        int line;
        int start;

        public Token(Kind kind, String token, int line, int start) {
            this.kind = kind;
            this.val = token;
            this.line = line;
            this.start = start;
        }

        String redText = "\u001B[31m";
        String resetText = "\u001B[0m"; // ANSI escape code to reset text color

        String errorAt(String msg) {
            return (line >= lines.length) ? "Invalid line number " + line
                    : "\n> " + lines[line] + "\n> " + " ".repeat(start) + redText + "^----" + msg + " at line "
                    + line + resetText;
        }

        @Override
        public String toString() {
            return val;
        }

        boolean delim(String s) {
            return kind == Kind.DELIMITER && val.equals(s);
        }

        boolean ident(String s) {
            return kind == Kind.IDENTIFIER && val.equals(s);
        }

        boolean isNumber() {
            return kind == Kind.NUMBER;
        }

        boolean isString() {
            return kind == Kind.STRING;
        }

        boolean isIdentifier() {
            return kind == Kind.IDENTIFIER;
        }

        boolean isEOF() {
            return kind == Kind.EOF;
        }
    }

    List<Token> tokenize(String[] lines) {
        var tokens = new ArrayList<Token>();
        for (int i = 0; i < lines.length; i++) {
            new Lexer(lines[i], i).tokenize(tokens);
        }
        return tokens;
    }

    class Lexer {

        String ln;
        int pos;
        int line_number;
        static String[] delimiters = {
            "{", "}", ":", "<", ">", ",", ";", "=", "(", ")", ".", ":", "$", "*", "+", "-",
            "/", "^", "?", "&", "|", "\\", "[", "]",
            "[", "]", "@", "#", "%", "~",};
        static String[] operators = {"<:", ">:", "==", "...", ">>", "<<", "::", ">>>"};

        Lexer(String ln, int line_number) {
            this.ln = ln;
            this.pos = 0;
            this.line_number = line_number;
        }

        void tokenize(List<Token> tokens) {
            Token tok = null;
            skipSpaces();
            while (pos < ln.length()) {
                if (tok == null) {
                    tok = number();
                }
                if (tok == null) {
                    tok = string();
                }
                if (tok == null) {
                    tok = character();
                }
                if (tok == null) {
                    tok = operators();
                }
                if (tok == null) {
                    tok = delimiter();
                }
                if (tok == null) {
                    tok = identifier();
                }
                if (tok == null && pos < ln.length()) {
                    failAt(ln.charAt(pos) + " is not a valid token", last);
                }
                tokens.add(last = tok);
                tok = null;
                skipSpaces();
            }
        }

        void skipSpaces() {
            while (pos < ln.length() && (ln.charAt(pos) == ' ' || ln.charAt(pos) == '\t')) {
                pos++;
            }
        }

        Token string() {
            char delim = ln.charAt(pos);
            if (delim != '"') {
                return null;
            }
            var start = pos++;
            while (pos < ln.length()) {
                if (ln.charAt(pos++) == '"') {
                    return new Token(Kind.STRING, ln.substring(start, pos), line_number, start);
                }
            }
            failAt("Unterminated string literal", last);
            return null;// not reached
        }

        Token character() {
            char delim = ln.charAt(pos);
            if (delim != '\'') {
                return null;
            }
            var start = pos++;
            while (pos < ln.length()) {
                if (ln.charAt(pos++) == '\'') {
                    return new Token(Kind.STRING, ln.substring(start, pos), line_number, start);
                }
            }
            failAt("Unterminated string literal", last);
            return null;// not reached
        }

        Token operators() {
            return eat(operators);
        }

        Token delimiter() {
            return eat(delimiters);
        }

        Token eat(String[] things) {
            for (String delim : things) {
                if (ln.startsWith(delim, pos)) {
                    var start = pos;
                    pos += delim.length();
                    return new Token(Kind.DELIMITER, delim, line_number, start);
                }
            }
            return null;
        }

        char readOrHuh(int p) {
            return p < ln.length() ? ln.charAt(p) : '?';
        }

        Token number() {
            var start = pos;
            var c0 = readOrHuh(pos);
            var c1 = readOrHuh(pos + 1);
            var c2 = readOrHuh(pos + 2);

            var startsWithDigit = Character.isDigit(c0);
            var startsWithDot = c0 == '.' && Character.isDigit(c1);
            var startsWithMinus = c0 == '-' && (Character.isDigit(c1) || (c1 == '.' && Character.isDigit(c2)));
            var isHex = c0 == '0' && (c1 == 'x' || c1 == 'X');
            if (!startsWithDigit && !startsWithDot && !startsWithMinus) {
                return null;
            }
            if (isHex) {
                pos += 2;
            } else if (startsWithDigit) {
                pos++;
            } else if (startsWithDot) {
                pos += 2;
            } else if (startsWithMinus) {
                pos += Character.isDigit(c1) ? 2 : 3;
            }
            while (pos < ln.length() && Character.isDigit(ln.charAt(pos))) {
                pos++;
            }
            if (pos < ln.length() && ln.charAt(pos) == '.') {
                pos++;
                while (pos < ln.length() && Character.isDigit(ln.charAt(pos))) {
                    pos++;
                }
            }
            return new Token(Kind.NUMBER, ln.substring(start, pos), line_number, start);
        }

        // I should really figure out a proper treatment of unicode...
        boolean unicodeUsed(int c) {
            return c == '∂' || c == '∘' || c == 'R' || c == 'ₜ' || c == '₋' || c == '₂' || c == '≡' || c == '≠' || c == '≤'
                    || c == '≥' || c == '∈' || c == '∉' || c == '∪' || c == '∩' || c == '⊆' || c == '⊇' || c == '⊂' || c == '⊃'
                    || c == '⊏' || c == '⊐' || c == '⊑' || c == '⊒' || c == '⊓' || c == '⊔' || c == '⊕' || c == '⊖' || c == '⊗'
                    || c == '′' || c == '𝕃' || c == '₀' || c == '𝕃' || c == '⊔' || c == '⋅'
                    || c == '∇' || c == 'μ' || c == '∑' || c == '₁' || c == '₂' || c == '𝒻' || c == 'τ' || c == 'Ω';
        }

        boolean identifierFirst(int c) {
            return Character.isLetter(c) || c == '_' || c == '!' || unicodeUsed(c);
        }

        boolean identifierRest(int c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '!' || unicodeUsed(c);
        }

        Token identifier() {
            var start = pos;

            if (!identifierFirst(ln.codePointAt(pos))) {
                return null;
            }
            pos++;
            while (pos < ln.length() && (identifierRest(ln.codePointAt(pos)))) {
                pos++;
            }
            return new Token(Kind.IDENTIFIER, ln.substring(start, pos), line_number, start);
        }
    }

    Parser advance() {
        if (curPos >= toks.size()) {
            failAt("Out of tokens", last);
        }
        curPos++;
        return this;
    }

    Token peek() {
        var tok = curPos < toks.size() ? toks.get(curPos) : eof();
        if (!tok.isEOF()) {
            last = tok;
        }
        return tok;
    }

    Token next() {
        var tok = peek();
        if (!tok.isEOF()) {
            advance();
        }
        last = tok;
        return tok;
    }

    Token nextIdentifier() {
        var tok = next();
        if (tok.kind != Kind.IDENTIFIER) {
            failAt("Expected an identifier but got " + tok, tok);
        }
        return tok;
    }

    void failAt(String msg, Token last) {
        if (toks != null) {
            for (Token token : toks) {
                if (token.line == last.line) {
                    System.out.println(token.val + " at line " + token.line + ", column " + token.start);
                }
            }
        }
        if (last == null) {
            System.out.println("At " + curPos);
            throw new RuntimeException("Huh?");
        } else {
            throw new RuntimeException(last.errorAt(msg));
        }
    }

}

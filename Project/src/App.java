
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class App {

    public static void main(String[] args) {
        var parser = new Parser("../types.jlg");
        while (true) {
            var typ = TypeDeclaration.parse(parser);
            if (typ == null)
                break;
            System.out.println(typ);
        }
        var methodParser = new Parser("../functions.jlg");
        while (true) {
            var method = methodParser.parseMethod();
            if (method == null)
                break;
            System.out.println(method);
        }
    }
}

class Parser {
    private String[] lines;
    private List<Token> toks;
    private Token last;
    private int curPos;

    public Parser(String path) {
        this.lines = getLines(path);
        this.toks = tokenize(lines);
        this.curPos = 0;
    }

    String[] getLines(String path) {
        try {
            Path p = Path.of(path);
            List<String> ls = Files.readAllLines(p);
            return ls.toArray(new String[0]);
        } catch (IOException e) {
            e.printStackTrace();
            return new String[0]; // Return an empty array in case of an exception.
        }
    }

    enum Kind {
        KEYWORD, OPERATOR, DELIMITER, IDENTIFIER, NUMBER, STRING;
    }

    class Token {
        Kind kind;
        String val;
        int line;
        int start;
        int end;

        public Token(Kind kind, String token, int line, int start, int end) {
            this.kind = kind;
            this.val = token;
            this.line = line;
            this.start = start;
            this.end = end;
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
    }

    List<Token> tokenize(String[] lines) {
        var tokens = new ArrayList<Token>();
        int linePos = 0;
        for (String line : lines) {
            var lexer = new Lexer(line, linePos);
            lexer.tokenize(tokens);
            linePos++;
        }
        return tokens;
    }

    class Lexer {
        String ln;
        int pos;
        int line_number;
        static String[] delimiters = {
                "{", "}", ":", "<", ">", ",", ";", "=", "(", ")", ".", ":", "$", "*", "+", "-",
                "/", "^", "!", "?", "&", "|",
                "[", "]", "@", "#", "%", "~", };
        static String[] operators = { "<:", ">:", "==", "...", ">>", "<<", "::", ">>>" };

        Lexer(String ln, int line_number) {
            this.ln = ln;
            this.pos = 0;
            this.line_number = line_number;
        }

        void tokenize(List<Token> tokens) {
            Token tok = null;
            while (pos < ln.length()) {
                skipSpaces();
                if (tok == null)
                    tok = string();
                if (tok == null)
                    tok = operators();
                if (tok == null)
                    tok = delimiter();
                if (tok == null)
                    tok = number();
                if (tok == null)
                    tok = identifier();
                if (tok == null && pos < ln.length())
                    fail(ln.charAt(pos) + " is not a valid token");
                tokens.add(tok);
                tok = null;
            }
        }

        void skipSpaces() {
            while (pos < ln.length() && (ln.charAt(pos) == ' ' || ln.charAt(pos) == '\t'))
                pos++;
        }

        Token string() {
            char delim = ln.charAt(pos);
            if (delim != '"')
                return null;
            var start = pos++;
            while (pos < ln.length())
                if (ln.charAt(pos++) == '"')
                    return new Token(Kind.STRING, ln.substring(start, pos), line_number, start, pos);
            fail("Unterminated string literal");
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
                    return new Token(Kind.DELIMITER, delim, line_number, start, pos);
                }
            }
            return null;
        }

        Token number() {
            var start = pos;
            if (!Character.isDigit(ln.charAt(pos)))
                return null;
            while (pos < ln.length() && Character.isDigit(ln.charAt(pos)))
                pos++;
            if (pos < ln.length() && ln.charAt(pos) == '.') {
                pos++;
                while (pos < ln.length() && Character.isDigit(ln.charAt(pos)))
                    pos++;
            }
            return new Token(Kind.NUMBER, ln.substring(start, pos), line_number, start, pos);
        }

        boolean identifierFirst(char c) {
            return Character.isLetter(c) || c == '_';
        }

        boolean identifierRest(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }

        Token identifier() {
            var start = pos;
            if (!identifierFirst(ln.charAt(pos)))
                return null;
            while (pos < ln.length() && (identifierRest(ln.charAt(pos))))
                pos++;
            return new Token(Kind.IDENTIFIER, ln.substring(start, pos), line_number, start, pos);
        }
    }

    Parser eat(String s) {
        Token tok = skip().peek();
        return (tok == null || !tok.val.equals(s)) ? null : advance().skip();
    }

    Parser eatOrDie(String s, String msg) {
        Token tok = skip().peek();
        if (tok == null || !tok.val.equals(s))
            fail(msg);
        return advance().skip();
    }

    Parser skip() {
        var tok = peek();
        while (tok != null) {
            for (char c : tok.val.toCharArray())
                if (c != ' ' && c != '\t')
                    return this;
            advance();
            tok = peek();
        }
        return this;
    }

    Parser advance() {
        curPos++;
        return this;
    }

    Token peek() {
        var tok = curPos < toks.size() ? toks.get(curPos) : null;
        if (tok != null)
            last = tok;
        return tok;
    }

    Token next() {
        var tok = peek();
        if (tok != null)
            advance();
        last = tok;
        return tok;
    }

    Token nextIdentifier() {
        var tok = next();
        if (tok == null || tok.kind != Kind.IDENTIFIER)
            fail("Expected an identifier but got " + tok);
        return tok;
    }

    private Token nextOrDie(String msg) {
        var tok = peek();
        if (tok == null)
            fail(msg);
        last = tok;
        advance();
        return tok;
    }

    void fail(String msg) {
        if (toks != null)
            for (Token token : toks)
                if (token.line == last.line)
                    System.out.println(token.val + " at line " + token.line + ", column " + token.start);
        throw new RuntimeException(last.errorAt(msg));
    }

    Method parseMethod() {
        eatOrDie("function", "Invalid method declaration");
        eat("(");
        var name = nextOrDie("Invalid method name").val;
        eat(")");
        eatOrDie("(", "Invalid method declaration");
        var params = new ArrayList<Argument>();
        var boundVars = new ArrayList<BoundVar>();

        if (eat(")") == null) {
            while (true) {
                params.add(parseArgument());
                if (eat(",") != null || eat(";") != null)
                    continue;
                else if (eat(")") != null)
                    break;
                else
                    fail("Invalid method parameter list");
            }
        }
        if (eat("::") != null) {
            parseType();
        }

        if (eat("where") != null) {
            eat("{");
            boundVars.add(parseBoundVar());
            while (eat(",") != null)
                boundVars.add(parseBoundVar());
            eat("}");
        }
        if (eat("where") != null) {
            eat("{");
            boundVars.add(parseBoundVar());
            while (eat(",") != null)
                boundVars.add(parseBoundVar());
            eat("}");
        }
        return new Method(name, params, boundVars);
    }

    Argument parseArgument() {
        var name = nextOrDie("Invalid argument name").val;
        Type type = new DataType("Any", null);
        if (name.equals("::")) {
            name = "??";
            type = parseBound();
        } else if (eat("::") != null)
            type = parseBound();
        eat("...");
        if (eat("=") != null) {
            next();
            if (eat("(") != null) {
                next();
                eat(")");
            }
        }
        return new Argument(name, type, null);
    }

    Declaration parseDeclaration() {
        if (eat("abstract") != null) {
            eatOrDie("type", "Invalid declaration");
            var name = nextOrDie("Invalid type name").val;
            var typeParams = new ArrayList<BoundVar>();
            if (eat("{") != null) {
                while (true) {
                    typeParams.add(parseBoundVar());
                    if (eat(",") != null)
                        continue;
                    else if (eat("}") != null)
                        break;
                    else
                        fail("Invalid type parameter list");
                }
            }
            if (eat("<:") != null) {
                var parent = parseType();
                eatOrDie("end", "Missed end of declaration");
                return new Declaration("abstract", name, typeParams, parent);
            } else {
                eatOrDie("end", "Missed end of declaration");
                return new Declaration("abstract", name, typeParams, null);
            }
        } else {
            eat("mutable");
            if (eat("struct") != null) {
                var name = nextOrDie("Invalid type name").val;
                var typeParams = new ArrayList<BoundVar>();
                if (eat("{") != null) {
                    while (true) {
                        typeParams.add(parseBoundVar());
                        if (eat(",") != null)
                            continue;
                        else if (eat("}") != null)
                            break;
                        else
                            fail("Invalid type parameter list");
                    }
                }
                if (eat("<:") != null) {
                    var parent = parseType();
                    eatOrDie("end", "Missed end of declaration");
                    return new Declaration("struct", name, typeParams, parent);
                } else {
                    eatOrDie("end", "Missed end of declaration");
                    return new Declaration("struct", name, typeParams, null);
                }
            } else
                return null;
        }
    }

    // Parse a single type (e.g., TypeName{Type1, Type2})
    private Type parseType() {
        var name = nextOrDie("Invalid type name").val;
        if (name.equals("typeof")) {
            if (eat("(") != null) {
                var expr = parseType();
                eat(")");
                return new DataType("typeof(" + expr.toString() + ")", null);
            } else {
                var expr = next();
                return new DataType("typeof(" + expr.val + ")", null);
            }
        }
        var typeParams = new ArrayList<Type>();
        if (eat("{") != null) {
            while (true) {
                if (eat("}") != null)
                    break;
                typeParams.add(parseBound());
                if (eat(",") != null)
                    continue;
                else if (eat("}") != null)
                    break;
                else
                    fail("Invalid type parameter list");
            }
        }
        return new DataType(name, typeParams);
    }

    // Parse a single bound variable (e.g., Type1 <: Type2)
    private BoundVar parseBoundVar() {
        if (eat("<:") != null) {
            var t0 = new DataType("?", null);
            var t1 = parseType();
            return new BoundVar(t0, null, t1);
        }
        var t0 = parseType();
        if (eat("<:") != null) {
            var t1 = parseType();
            if (eat("<:") != null) {
                var t2 = parseType();
                return new BoundVar(t1, t0, t2);
            } else
                return new BoundVar(t1, t0, null);
        } else if (eat(">:") != null) {
            var t1 = parseType();
            return new BoundVar(t0, t1, null);
        } else
            return new BoundVar(t0, null, null);
    }

    // Parse a bound (e.g., TypeName where Type1 <: Type2, Type3)
    private Type parseBound() {
        var type = parseBoundVar();
        if (eat("where") == null)
            return type;
        var boundVars = new ArrayList<BoundVar>();
        boundVars.add(parseBoundVar());
        while (eat(",") != null)
            boundVars.add(parseBoundVar());
        return new Bound(type, boundVars);
    }

}

class TypeName {
    String name;

    TypeName(String name) {
        this.name = name;
    }

    static TypeName parse(Parser p) {
        var str = p.nextIdentifier().toString();
        var next = p.peek();
        while (next != null && next.delim(".")) {
            p.next();
            str += next + p.nextIdentifier().toString();
        }
        return new TypeName(str);
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
                str += p.next().toString();
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

// X
// X <: Y
// X <: Y <: Z
// X >: Y
// <: Y
// >: Y
// X <: Int{Y} where Y
class TypeDeclParam {
    static TypeDeclParam parse(Parser p) {
        return null;
    }
}

class TypeTerm {
    static TypeTerm parse(Parser p) {
        var ti = TypeInst.parse(p);
        var tok = p.peek();
        if (tok.delim("where")) {
            p.next();
            var boundVars = new ArrayList<BoundTypeVar>();
            boundVars.add((BoundTypeVar) BoundTypeVar.parse(p));
            while (p.peek().delim(","))
                boundVars.add((BoundTypeVar) BoundTypeVar.parse(p));
            return new UnionAllInst(ti, boundVars);
        } else
            return ti;
    }
}

// Int
// Int{X}
// Int{X,Y}
class TypeInst extends TypeTerm {
    TypeName name;
    List<TypeTerm> typeParams;

    TypeInst(TypeName name, List<TypeTerm> typeParams) {
        this.name = name;
        this.typeParams = typeParams;
    }

    static TypeInst parse(Parser p) {
        var name = TypeName.parse(p);
        System.out.println("Type name " + name);
        var typeParams = new ArrayList<TypeTerm>();
        var tok = p.peek();
        if (tok.delim("{")) {
            p.next();
            tok = p.peek();
            while (!tok.delim("}")) {
                if (tok.delim(",")) {
                    p.next();
                    continue;
                } else {
                    System.out.println("Tok " + tok.errorAt("here"));
                    typeParams.add(TypeTerm.parse(p));
                }
                tok = p.peek();
            }
            p.next();
        }
        return new TypeInst(name, typeParams);
    }

    @Override
    public String toString() {
        var str = name.toString();
        if (typeParams != null && !typeParams.isEmpty()) {
            str += "{";
            for (int i = 0; i < typeParams.size(); i++) {
                str += typeParams.get(i).toString();
                if (i < typeParams.size() - 1)
                    str += ", ";
            }
            str += "}";
        }
        return str;
    }
}

// X
// X <: Y
// X <: Y <: Z
// X >: Y
// <: Y
// >: Y
// X <: Int{Y} where Y
class BoundTypeVar extends TypeTerm {
    TypeName name;
    TypeTerm lower;
    TypeTerm upper;

    BoundTypeVar(String name, TypeTerm lower, TypeTerm upper) {
        this.name = new TypeName(name);
        this.lower = lower;
        this.upper = upper;
    }

    static TypeTerm parse(Parser p) {
        var tok = p.peek();
        if (tok.delim("<:")) {
            var upper = UnionAllInst.parse(p.advance());
            return new BoundTypeVar("???", null, upper);
        } else {
            var t = UnionAllInst.parse(p);
            tok = p.peek();
            if (tok.delim("<:")) {
                var upper = UnionAllInst.parse(p.advance());
                return new BoundTypeVar(t.toString(), null, upper);
            } else if (tok.delim(">:")) {
                var lower = UnionAllInst.parse(p.advance());
                return new BoundTypeVar(t.toString(), lower, null);
            } else
                return new BoundTypeVar(t.toString(), null, null);
        }
    }

    @Override
    public String toString() {
        var str = name.toString();
        if (lower != null)
            str = lower.toString() + " <: " + str;
        if (upper != null)
            str = str + " <: " + upper.toString();
        return str;
    }
}

// Int{X} where {Z <: X <: Y, Y <: Int} where {X <: Int}
class UnionAllInst extends TypeTerm {
    TypeInst type;
    List<BoundTypeVar> boundVars;

    UnionAllInst(TypeInst type, List<BoundTypeVar> boundVars) {
        this.type = type;
        this.boundVars = boundVars;
    }

    static TypeTerm parse(Parser p) {
        var type = TypeInst.parse(p);
        var tok = p.peek();
        if (tok.delim("where")) {
            p.next();
            var boundVars = new ArrayList<BoundTypeVar>();
            boundVars.add((BoundTypeVar) BoundTypeVar.parse(p));
            while (p.peek().delim(","))
                boundVars.add((BoundTypeVar) BoundTypeVar.parse(p));
            return new UnionAllInst(type, boundVars);
        } else
            return null;
    }

    @Override
    public String toString() {
        var str = type.toString();
        if (boundVars != null && !boundVars.isEmpty()) {
            str += " where ";
            for (int i = 0; i < boundVars.size(); i++) {
                str += boundVars.get(i).toString();
                if (i < boundVars.size() - 1)
                    str += ", ";
            }
        }
        return str;
    }
}

class TypeDeclaration {
    String modifiers;
    TypeName name;
    List<TypeTerm> typeParams;
    TypeTerm parent;

    TypeDeclaration(String modifiers, TypeName name, List<TypeTerm> typeParams, TypeTerm parent) {
        this.modifiers = modifiers;
        this.name = name;
        this.typeParams = typeParams;
        this.parent = parent;
    }

    static String parseModifiers(Parser p) {
        var str = "";
        var tok = p.next();
        if (tok.ident("abstract")) {
            str += "abstract ";
            tok = p.next();
            if (tok.ident("type")) {
                str += "type ";
            } else
                p.fail("Invalid type declaration");
            return str;
        }
        if (tok.ident("mutable")) {
            str += "mutable ";
            tok = p.next();
        }
        if (tok.ident("struct")) {
            str += "struct ";
            return str;
        } else
            p.fail("Invalid type declaration");
        return null;
    }

    static TypeDeclaration parse(Parser p) {
        var modifiers = parseModifiers(p);
        var name = TypeName.parse(p);
        var typeParams = new ArrayList<TypeTerm>();
        var tok = p.peek();
        if (tok.delim("{")) {
            tok = p.advance().peek();
            while (!tok.delim("}")) {
                if (tok.delim(",")) {
                    p.next();
                    continue;
                } else
                    typeParams.add(TypeTerm.parse(p));
                tok = p.peek();
            }
            p.advance();
        }
        tok = p.next();
        if (tok.ident("end"))
            return new TypeDeclaration(modifiers, name, typeParams, null);
        else if (tok.delim("<:")) {
            var parent = TypeTerm.parse(p);
            tok = p.next();
            if (!tok.ident("end"))
                p.fail("Missed end of declaration");
            return new TypeDeclaration(modifiers, name, typeParams, parent);
        } else {
            p.fail("Invalid type declaration");
            return null; // not reached
        }
    }

    @Override
    public String toString() {
        var str = modifiers + name.toString();
        if (typeParams != null && !typeParams.isEmpty()) {
            str += "{";
            for (int i = 0; i < typeParams.size(); i++) {
                str += typeParams.get(i).toString();
                if (i < typeParams.size() - 1)
                    str += ", ";
            }
            str += "}";
        }
        if (parent != null)
            str += " <: " + parent.toString();
        return str;
    }
}

class Declaration {
    String kind;
    String name;
    List<BoundVar> typeParams;
    Type parent;

    Declaration(String kind, String name, List<BoundVar> typeParams, Type parent) {
        this.kind = kind;
        this.name = name;
        this.typeParams = typeParams;
        this.parent = parent;
    }

    @Override
    public String toString() {
        var str = new StringBuilder(kind + " " + name);
        if (typeParams != null && !typeParams.isEmpty()) {
            str.append("{");
            for (int i = 0; i < typeParams.size(); i++) {
                str.append(typeParams.get(i).toString());
                if (i < typeParams.size() - 1)
                    str.append(", ");
            }
            str.append("}");
        }
        if (parent != null)
            str.append(" <: ").append(parent.toString());
        return str.toString();
    }
}

class Argument {
    String name;
    Type type;
    String defaultValue;

    Argument(String name, Type type, String defaultValue) {
        this.name = name;
        this.type = type;
        this.defaultValue = defaultValue;
    }

    @Override
    public String toString() {
        return name + " :: " + type.toString();
    }
}

class Method {
    String name;
    List<Argument> args;
    List<BoundVar> boundVars;

    public Method(String name, List<Argument> args, List<BoundVar> boundVars) {
        this.name = name;
        this.args = args;
        this.boundVars = boundVars;
    }

    @Override
    public String toString() {
        var str = new StringBuilder(
                "function " + name + "(");
        for (int i = 0; i < args.size(); i++) {
            str.append(args.get(i).toString());
            if (i < args.size() - 1)
                str.append(", ");
        }
        str.append(")");
        if (boundVars != null && !boundVars.isEmpty()) {
            str.append(" where ");
            for (int i = 0; i < boundVars.size(); i++) {
                str.append(boundVars.get(i).toString());
                if (i < boundVars.size() - 1)
                    str.append(", ");
            }
        }
        return str.toString();
    }
}

class Type {
}

class DataType extends Type {
    private final String name;
    private final List<Type> params;

    public DataType(String name, List<Type> typeParams) {
        this.name = name;
        this.params = typeParams;
    }

    @Override
    public String toString() {
        if (params == null || params.isEmpty())
            return name;
        var str = new StringBuilder("{");
        for (int i = 0; i < params.size(); i++) {
            str.append(params.get(i).toString());
            if (i < params.size() - 1)
                str.append(", ");
        }
        return name + str.append("}").toString();
    }
}

class BoundVar extends Type {
    private final Type variable;
    private final Type upper;
    private final Type lower;

    public BoundVar(Type variable, Type lower, Type upper) {
        this.variable = variable;
        this.upper = upper;
        this.lower = lower;
    }

    @Override
    public String toString() {
        var res = variable.toString();
        if (lower != null)
            res = lower.toString() + " <: " + res;
        if (upper != null)
            res = res + " <: " + upper.toString();
        return res;
    }
}

class Bound extends Type {
    private final Type type;
    private final List<BoundVar> boundVars;

    public Bound(Type type, List<BoundVar> boundVars) {
        this.type = type;
        this.boundVars = boundVars;
    }

    @Override
    public String toString() {
        var str = new StringBuilder(type.toString());
        if (boundVars != null && !boundVars.isEmpty()) {
            str.append(" where ");
            for (int i = 0; i < boundVars.size(); i++) {
                str.append(boundVars.get(i).toString());
                if (i < boundVars.size() - 1)
                    str.append(", ");
            }
        }
        return str.toString();
    }
}

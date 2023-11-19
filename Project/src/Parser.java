
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class Parser {
    private String[] lines;
    private List<Token> toks;
    private Token last;
    private int curPos;
    boolean verbose = true;

    public Parser() {
        this.curPos = 0;
    }

    public Parser withFile(String path) {
        this.lines = getLines(path);
        this.toks = tokenize(lines);
        return this;
    }

    public Parser withString(String s) {
        this.lines = s.split("\n");
        this.toks = tokenize(lines);
        return this;
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

    String getLine() {
        return lines[last.line];
    }

    enum Kind {
        KEYWORD, OPERATOR, DELIMITER, IDENTIFIER, NUMBER, STRING, EOF;
    }

    private Token EOF = new Token(Kind.EOF, "EOF", 0, 0, 0);

    Token eof() {
        return EOF;
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
        for (int i = 0; i < lines.length; i++)
            new Lexer(lines[i], i).tokenize(tokens);
        return tokens;
    }

    class Lexer {
        String ln;
        int pos;
        int line_number;
        static String[] delimiters = {
                "{", "}", ":", "<", ">", ",", ";", "=", "(", ")", ".", ":", "$", "*", "+", "-",
                "/", "^", "?", "&", "|", "\\", "[", "]",
                "[", "]", "@", "#", "%", "~", };
        static String[] operators = { "<:", ">:", "==", "...", ">>", "<<", "::", ">>>" };

        Lexer(String ln, int line_number) {
            this.ln = ln;
            this.pos = 0;
            this.line_number = line_number;
        }

        void tokenize(List<Token> tokens) {
            Token tok = null;
            skipSpaces();
            while (pos < ln.length()) {
                if (tok == null)
                    tok = number();
                if (tok == null)
                    tok = string();
                if (tok == null)
                    tok = character();
                if (tok == null)
                    tok = operators();
                if (tok == null)
                    tok = delimiter();
                if (tok == null)
                    tok = identifier();
                if (tok == null && pos < ln.length())
                    failAt(ln.charAt(pos) + " is not a valid token", last);
                tokens.add(last = tok);
                tok = null;
                skipSpaces();
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
            failAt("Unterminated string literal", last);
            return null;// not reached
        }

        Token character() {
            char delim = ln.charAt(pos);
            if (delim != '\'')
                return null;
            var start = pos++;
            while (pos < ln.length())
                if (ln.charAt(pos++) == '\'')
                    return new Token(Kind.STRING, ln.substring(start, pos), line_number, start, pos);
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
                    return new Token(Kind.DELIMITER, delim, line_number, start, pos);
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
            if (!startsWithDigit && !startsWithDot && !startsWithMinus)
                return null;
            if (isHex)
                pos += 2;
            else if (startsWithDigit)
                pos++;
            else if (startsWithDot)
                pos += 2;
            else if (startsWithMinus)
                pos += Character.isDigit(c1) ? 2 : 3;
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
            return Character.isLetter(c) || c == '_' || c == '!';
        }

        boolean identifierRest(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '′' || c == '!';
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

    Parser advance() {
        if (curPos >= toks.size())
            failAt("Out of tokens", last);
        curPos++;
        return this;
    }

    Token peek() {
        var tok = curPos < toks.size() ? toks.get(curPos) : eof();
        if (!tok.isEOF())
            last = tok;
        return tok;
    }

    Token next() {
        var tok = peek();
        if (!tok.isEOF())
            advance();
        last = tok;
        return tok;
    }

    Token nextIdentifier() {
        var tok = next();
        if (tok.kind != Kind.IDENTIFIER)
            failAt("Expected an identifier but got " + tok, tok);
        return tok;
    }

    void failAt(String msg, Token last) {
        if (toks != null)
            for (Token token : toks)
                if (token.line == last.line)
                    System.out.println(token.val + " at line " + token.line + ", column " + token.start);
        throw new RuntimeException(last.errorAt(msg));
    }

}

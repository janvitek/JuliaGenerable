
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

        boolean isNumber() {
            return kind == Kind.NUMBER;
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

    void fail(String msg) {
        if (toks != null)
            for (Token token : toks)
                if (token.line == last.line)
                    System.out.println(token.val + " at line " + token.line + ", column " + token.start);
        throw new RuntimeException(last.errorAt(msg));
    }

}

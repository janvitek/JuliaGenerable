package prlprg;

import java.util.ArrayList;
import java.util.List;

import static prlprg.CodeColors.color;

/**
 * Translates strings into tokens. The new lexer uses a different design than
 * the old one which should be easier to change.
 * 
 * Each line of code is represented by an object taht has a list of tokens in
 * it. When we return a list of tokens, we add the EOF token at the end for
 * backward compatibiltiy.
 */
class Lexer {

  public static void main(String[] args) {
    new Line(" var\"#s92\"} where var\"#s92\"", 1);
    //var line = new Line("const a.aa = s   <: b.bb@ == (22.2)::T.:a2 ", 1);
  }

  /** The lines from the file. */
  List<Line> lines = new ArrayList<>();

  /** Create a Lexder from an array of strings. */
  Lexer(String[] lns) {
    for (int i = 0; i < lns.length; i++)
      lines.add(new Line(lns[i], i));
  }

  /** Create an empty lexer. */
  Lexer() {
    this(new String[0]);
  }

  /** Returns the next list of tokens, or an empty list if there are no more. */
  List<Tok> next() {
    if (lines.isEmpty()) return new ArrayList<>();
    var line = lines.removeFirst();
    while (line.tokens.isEmpty() && !lines.isEmpty())
      line = lines.removeFirst();
    return line.tokens;
  }

}

/** One line of the original input. */
class Line {

  /** The original line. */
  protected final String line;
  /** The line number. */
  protected final int lineNumber;
  /** The tokens in the line. */
  protected List<Tok> tokens = new ArrayList<>();

  /**
   * Create a Line given a string and its position in the source file.
   */
  Line(String line, int lineNumber) {
    this.line = line;
    this.lineNumber = lineNumber;

    // Go over the string of Unicode characters and create  UNKNWON tokens
    // for each character.
    // On the way: create tokesn for character constants and string constants.
    int pos = 0;
    while (!atEnd(pos)) {
      var start = pos;
      var ret = readString(pos);
      if (ret != -1) {
        tokens.add(new Tok(this, Kind.STRING, start, pos = ret, pos - start));
        continue;
      }
      ret = readChar(pos);
      if (ret != -1) {
        tokens.add(new Tok(this, Kind.CHAR, start, pos = ret, pos - start));
        continue;
      }
      if (atEnd(pos)) break;
      var ch = charAt(pos);
      pos += increment(pos);
      if (Character.isWhitespace(ch)) continue; // skip spaces
      tokens.add(new Tok(this, Kind.UNKNOWN, start, pos, 1));
    }
    // Add an EOF token. This means that we don't need to worry about visiting the last token. 
    tokens.addLast(new Tok(this, Kind.EOF, 0, 0, 0));

    // Passes of lexing.
    tokens = Visitor.visit(tokens, new FirstPass()); // Most things
    tokens = Visitor.visit(tokens, new Operators()); // Operators === and &&
    tokens = Visitor.visit(tokens, new Dotted()); // a.b. and 2.2
    tokens = Visitor.visit(tokens, new DottedIdents()); // a.b.==

    // Sanity check
    for (var t : tokens)
      if (t.k() == Kind.UNKNOWN) throw new RuntimeException("Should not be there");

    System.out.println(this); // TODO: remove
  }

  /** Return the Unicode char at that position. */
  private int charAt(int pos) {
    return line.codePointAt(pos);
  }

  /** Return the number of code points in the character at that position. */
  private int increment(int pos) {
    return Character.charCount(charAt(pos));
  }

  /** Return true if the position is at the end of the line. */
  private boolean atEnd(int pos) {
    return pos >= line.length();
  }

  /**
   * Return the position after reading a character constant: 'a' or '\''. Return
   * -1 if not a char constant.
   */
  private int readChar(int pos) {
    var ret = -1; // means it was not a char constant
    if (atEnd(pos)) return ret; // at end of string, this is not a char
    var ch = charAt(pos);
    pos += increment(pos);
    if (ch != '\'' || atEnd(pos)) return ret; // does not start with a single quote
    ch = charAt(pos);
    pos += increment(pos);
    if (ch == '\'' && !atEnd(pos)) pos += increment(pos); // seen an escape character, skip the next one
    if (atEnd(pos)) return ret; // ill formed char constant, the line ended early
    ch = charAt(pos);
    pos += increment(pos);
    if (ch != '\'') return ret; // ill formed char constant, there is no closing single quote
    return pos; // success return how many characters we read
  }

  /**
   * Return the position after reading a string constant: "a" or "\"". Return -1
   * if not a string constant.
   */
  private int readString(int pos) {
    var ret = -1;
    if (atEnd(pos)) return ret; // already at the end of the line
    var ch = charAt(pos);
    pos += increment(pos);
    if (ch != '\"' || atEnd(pos)) return ret; // does not start with a double quote or at end just after
    while (!atEnd(pos)) {
      ch = charAt(pos);
      pos += increment(pos);
      if (ch == '\'' && !atEnd(pos)) pos += increment(pos);
      if (atEnd(pos)) return ret;
      if (ch == '\"') return pos;
    }
    return ret;
  }

  /** Return the line number. */
  public int getLineNumber() {
    return lineNumber;
  }

  /**
   * Return the line as a string along with the orignal and the token by token.
   */
  public String toString() {
    var sb = new StringBuilder();
    var sb2 = new StringBuilder();
    for (var token : tokens) {
      sb.append(token);
      sb2.append(token);
      sb2.append("'");
    }
    return "orig=|" + line + "|\nlexd=|" + sb.toString() + "|\n" + sb2.toString() + "|\n";
  }

}

/**
 * The kind of token. Mostly self-explanatory. Except: UNKNOWN is the starting
 * state for all tokens, part of lexing is to figure out what kind of token it
 * is. SPACE is for all whitespaces these will be dropped. EOF is the end of
 * line token added when sending values to the parser.
 */
enum Kind {
  IDENTIFIER, NUMBER, STRING, CHAR, DELIM, UNKNOWN, OPERATOR, EOF;
}

/**
 * A token in the input with the enclosing line in which it occurs, its kind,
 * and start and end positions
 */
record Tok(Line l, Kind k, int start, int end, int length) {

  /** Returns the original source like */
  String getLine() {
    return l.line;
  }

  /** Returns the line number */
  int lineNumber() {
    return l.lineNumber;
  }

  /** Returns true if the tokens are adjacent */
  protected boolean adjacent(Tok other) {
    return end == other.start;
  }

  /** Merge two adjacent tokens into a larger one of the given kind. */
  protected Tok merge(Tok other, Kind kind) {
    return new Tok(l, kind, start, other.end, length + other.length);
  }

  /**
   * Merge two adjacent tokens into a larger one of the same kind as the first.
   */
  protected Tok merge(Tok other) {
    if (k == Kind.UNKNOWN) throw new RuntimeException("Unknown token");
    return merge(other, k);
  }

  /** Returns true if the token is EOF */
  boolean isEOF() {
    return k == Kind.EOF;
  }

  /** Returns true if the token is STRING */
  boolean isString() {
    return k == Kind.STRING;
  }

  /**
   * Returns true if the token is DELIM or if it can be turned into a delimiter
   */
  boolean isDelim() {
    if (k == Kind.DELIM) return true;
    if (k != Kind.UNKNOWN) return false;
    if (length == 1) {
      int ch = getLine().codePointAt(start);
      return ch == '(' || ch == ')' || ch == '[' || ch == ']' || ch == '{' || ch == '}' || ch == ',' || ch == ';' || ch == '?';
    }
    return false;
  }

  /** Returns this token with kind set to DELIM */
  protected Tok asDelim() {
    return new Tok(l, Kind.DELIM, start, end, length);
  }

  /** Returns true if the token is a single character with value c */
  boolean isChar(char c) {
    return length == 1 && getLine().codePointAt(start) == c;
  }

  /** Returns this token with kind set to numbers */
  protected Tok asNumber() {
    return new Tok(l, Kind.NUMBER, start, end, length);
  }

  /** Returns true if the token is a number */
  boolean isNumber() {
    if (k == Kind.NUMBER) return true;
    if (k != Kind.UNKNOWN) return false;
    if (length == 1) {
      int ch = getLine().codePointAt(start);
      return Character.isDigit(ch);
    }
    return false;
  }

  /** Returns true if the token is an operator or can be turned into one. */
  boolean isOperator() {
    if (k == Kind.OPERATOR) return true;
    if (k != Kind.UNKNOWN) return false;
    if (isDelim()) return false;
    if (length != 1) return false;
    var ch = getLine().codePointAt(start);
    if (ch == '.') return false; // dots are kind of delimiters
    if (ch == '_') return false; // underscores are kind of identifiers
    if (Character.isAlphabetic(ch)) return false;
    if (Character.isDigit(ch)) return false;
    return true; // Guess
  }

  /** Returns this token with kind set to OPERATOR */
  protected Tok asOperator() {
    return new Tok(l, Kind.OPERATOR, start, end, length);
  }

  /** Returns true if the token is an identifier or can be turned into one. */
  boolean isIdent() {
    if (k == Kind.IDENTIFIER) return true;
    if (length == 1) {
      int ch = getLine().codePointAt(start);
      if (ch == '_') return true;
      return Character.isAlphabetic(ch);
    }
    return false;
  }

  /** Returns this token with kind set to IDENTIFIER */
  protected Tok asIdent() {
    return new Tok(l, Kind.IDENTIFIER, start, end, length);
  }

  /** Returns true if the token ends with the given character */
  boolean endsWith(char c) {
    return length > 0 && getLine().codePointAt(end - 1) == c;
  }

  /** Returns true if the token ends with the given string */
  boolean startsWith(String s) {
    return length >= s.length() && getLine().substring(start, start + s.length()).equals(s);
  }

  /** Returns true if the token is the given string */
  boolean is(String s) {
    return length == s.length() && getLine().substring(start, end).equals(s);
  }

  public String toString() {
    return length == 0 ? "" : (k == Kind.EOF ? "<END>" : (k == Kind.UNKNOWN ? "?" : getLine().substring(start, end)));
  }

  String errorAt(String msg) {
    return "\n> " + getLine() + "\n> " + " ".repeat(start) + color("^----" + msg + " at line " + lineNumber(), "Red");
  }

}

/** A class for traversing a token sequence. */
class Visitor {
  private List<Tok> from; // source tokens
  private List<Tok> to; // destination tokens

  /** Create a visitor from a list of tokens. This method is not exposed. */
  private Visitor(List<Tok> from) {
    this.from = from;
    this.to = new ArrayList<>();
  }

  /**
   * Given a list of tokens and a transformer, reuturn the transformer list of
   * tokens.
   */
  static List<Tok> visit(List<Tok> from, Transformer e) {
    return new Visitor(from).visit(e);
  }

  /**
   * Internal method that takes from the head of the source, and adding to the end
   * of the target list. The game is to put things on the target list when we are
   * done with them, and put things back on the source list if they need to be
   * revisited.
   */
  private List<Tok> visit(Transformer e) {
    while (!from.isEmpty()) {
      var tok = from.removeFirst();
      if (from.isEmpty())
        to.add(tok); // the last token is a space, there is no processing for those
      else
        e.accept(tok, from.removeFirst(), this);
    }
    return to;
  }

  /** We are done with this token, add it to the target list. */
  protected Visitor done(Tok t) {
    to.addLast(t);
    return this;
  }

  /** We are done with these two tokens, add them to the target list. */
  protected Visitor done(Tok t1, Tok t2) {
    to.addLast(t1);
    to.addLast(t2);
    return this;
  }

  /** We are done with these three tokens, add them to the target list. */
  protected Visitor done(Tok t1, Tok t2, Tok t3) {
    to.addLast(t1);
    to.addLast(t2);
    to.addLast(t3);
    return this;
  }

  /** We have looked at this token, but need to see it again. */
  protected Visitor revisit(Tok t) {
    from.addFirst(t);
    return this;
  }
}

/**
 * Interface for transformers. They have a single method that takes two tokens
 * and a visitor. The visitor is used for callbacks to rdecide which tokens we
 * are done with and which tokens need to be revisited. (Note: the visitor is
 * could be passed to the constructor of the transformer, but that would need a
 * constructor for each transformer, a couple more lines of code)
 */
class Transformer {
  void accept(Tok t1, Tok t2, Visitor v) {
  }
}

/**
 * The first pass of lexing. This handles delimiters (e.g. '[') as well as "::",
 * "...", numbbers, identifiers.
 */
class FirstPass extends Transformer {
  void accept(Tok t1, Tok t2, Visitor v) {
    if (t1.isDelim())
      v.done(t1.asDelim()).revisit(t2);
    else if (t1.adjacent(t2) && t1.isChar(':') && t2.isChar(':')) // ::
      v.done(t1.asDelim().merge(t2));
    else if (t1.adjacent(t2) && t1.isNumber() && t2.isNumber())
      v.revisit(t1.asNumber().merge(t2));
    else if (t1.adjacent(t2) && t1.isIdent() && t2.isIdent())
      v.revisit(t1.asIdent().merge(t2));
    else if (t1.adjacent(t2) && t1.isIdent() && t2.isNumber()) // naem3
      v.revisit(t1.asIdent().merge(t2));
    else if (t1.adjacent(t2) && t1.isIdent() && t2.isChar('\'')) // name'
      v.revisit(t1.asIdent().merge(t2));
    else if (t1.adjacent(t2) && t1.isIdent() && t2.isChar('!')) // name!
      v.revisit(t1.asIdent().merge(t2));
    else if (t1.adjacent(t2) && t1.isChar('.') && t2.isChar('.')) // ...
      v.revisit(t1.asOperator().merge(t2));
    else if (t1.adjacent(t2) && t1.is("..") && t2.isChar('.')) // ...
      v.revisit(t1.merge(t2));
    else if (t1.adjacent(t2) && t1.isChar('@') && t2.isChar('_')) // @__Module
      v.revisit(t1.asIdent().merge(t2));
    else if (t1.adjacent(t2) && t1.isChar('@') && t2.isIdent()) // @soft
      v.revisit(t1.asIdent().merge(t2));
    else if (t1.adjacent(t2) && t1.isChar('%') && t2.isNumber()) // handle register names, could mess up arithemtic ege 3%4 will be '3' '%4'
      v.revisit(t1.asIdent().merge(t2));
    else if (t1.adjacent(t2) && t1.isChar('#') && t2.isIdent()) // #self#
      v.revisit(t1.asIdent().merge(t2));
    else if (t1.adjacent(t2) && t1.isIdent() && t2.isChar('#')) // #self#
      v.revisit(t1.asIdent().merge(t2));
    else if (t1.adjacent(t2) && t1.isChar('0') && t2.isChar('x')) // 0x12
      v.revisit(t1.asNumber().merge(t2));
    else if (t1.adjacent(t2) && t1.isIdent() && t2.isString()) // var"#1343"
      v.revisit(t1.merge(t2));
    else if (t1.isNumber())
      v.done(t1.asNumber()).revisit(t2);
    else if (t1.isIdent())
      v.done(t1.asIdent()).revisit(t2);
    else
      v.done(t1).revisit(t2);
  }

}

class Dotted extends Transformer {
  void accept(Tok t1, Tok t2, Visitor v) {
    if (t1.adjacent(t2) && (t1.isIdent() || t1.isNumber()) && t2.isChar('.'))
      v.revisit(t1.merge(t2));
    else
      v.done(t1).revisit(t2);
  }
}

class DottedIdents extends Transformer {
  void accept(Tok t1, Tok t2, Visitor v) {
    if (t1.adjacent(t2) && t1.isIdent() && t1.endsWith('.') && (t2.isIdent() || t2.isNumber() || t2.isOperator()))
      v.revisit(t1.merge(t2));
    else if (t1.adjacent(t2) && t1.isIdent() && t1.endsWith('.') && t2.isNumber())
      v.revisit(t1.merge(t2));
    else
      v.done(t1).revisit(t2);
  }
}

class Operators extends Transformer {
  void accept(Tok t1, Tok t2, Visitor v) {
    if (t1.adjacent(t2) && t1.isOperator() && t2.isOperator())
      v.revisit(t1.asOperator().merge(t2));
    else if (t1.adjacent(t2) && t1.isChar(':') && t2.isIdent())
      v.revisit(t1.asIdent().merge(t2));
    else
      v.done(t1.isOperator() ? t1.asOperator() : t1).revisit(t2);
  }
}
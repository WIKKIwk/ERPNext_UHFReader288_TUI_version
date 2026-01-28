package uhf.core;

public record Result(boolean ok, int code) {
  public static Result success() {
    return new Result(true, 0);
  }

  public static Result fail(int code) {
    return new Result(false, code);
  }
}

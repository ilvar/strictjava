package app;

public final class Main {
  private Main() {}

  static String greeting() {
    return "Hello from __PROJECT__.";
  }

  public static void main(String[] args) {
    System.out.println(greeting());
  }
}

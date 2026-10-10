package android.os;
public class Looper {
    final HandlerThread owner;
    Looper(HandlerThread owner) { this.owner = owner; }
}

package g;
@Service
public class C {
    private final A a;
    public C(A a, @Qualifier("x") B b) { this.a = a; }
}

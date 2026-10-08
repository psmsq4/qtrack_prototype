package g;
public class C {
    @Autowired private A a;
    @Autowired @Qualifier("fast") private B b;
    @Resource(name = "named") private D d;
    private E notInjected;
}

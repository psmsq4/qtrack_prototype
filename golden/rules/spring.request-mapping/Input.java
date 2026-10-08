package g;
@Controller
@RequestMapping(value = "/orders")
public class C {
    @GetMapping
    public void list() {}
    @PostMapping({"/new", "/create"})
    public void create() {}
    @RequestMapping(path = "/{id}", method = RequestMethod.DELETE)
    public void delete() {}
    @org.springframework.web.bind.annotation.PatchMapping("/{id}")
    public void patch() {}
    @PutMapping(value = "/bulk")
    public void bulk() {}
    public void notMapped() {}
}

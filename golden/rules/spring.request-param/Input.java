package g;
public class C {
    public void m(@RequestParam("a") String a, @RequestParam(name = "bb", required = false) String b,
                  @PathVariable String id, @RequestBody Dto body, @RequestHeader("X-Token") String token, String plain) {}
}

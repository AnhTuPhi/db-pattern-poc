package com.claude.dbpoc.m21.web;

import com.claude.dbpoc.m21.service.ReplicaService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/replica")
public class ReplicaController {

    private final ReplicaService svc;

    public ReplicaController(ReplicaService svc) { this.svc = svc; }

    @PostMapping("/seed")
    public Map<String, Object> seed() { return svc.seed(); }

    @PostMapping("/write")
    public Map<String, Object> write(@RequestParam Long id, @RequestParam BigDecimal balance) {
        return svc.write(id, balance);
    }

    @GetMapping("/read")
    public Map<String, Object> read(@RequestParam Long id) { return svc.read(id); }

    @GetMapping("/lag-demo")
    public Map<String, Object> lagDemo(@RequestParam Long id,
                                       @RequestParam BigDecimal balance,
                                       @RequestParam(defaultValue = "300") long lagMs) {
        return svc.lagDemo(id, balance, lagMs);
    }

    @GetMapping("/sticky-read")
    public Map<String, Object> sticky(@RequestParam Long id,
                                      @RequestParam BigDecimal balance,
                                      @RequestParam(defaultValue = "300") long lagMs,
                                      @RequestParam(defaultValue = "500") long stickyWindowMs) {
        return svc.stickyRead(id, balance, lagMs, stickyWindowMs);
    }
}

package com.blackbox.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardingController {

    @GetMapping({
            "/",
            "/dashboard",
            "/activity",
            "/repositories"
    })
    public String forwardToReact() {
        return "forward:/index.html";
    }
}

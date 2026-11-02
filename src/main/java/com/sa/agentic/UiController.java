package com.sa.agentic;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The dashboard is a static page under /ui; make the obvious entry points land on it. */
@Controller
@Hidden
public class UiController {

    @GetMapping({"/", "/ui", "/ui/"})
    public String dashboard() {
        return "redirect:/ui/index.html";
    }
}

package ru.example.ukep.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Публичные HTML-страницы верификации подписи.
 * Логика — на клиенте (fetch /api/verify/...).
 */
@Controller
public class PublicVerifyPageController {

    @GetMapping("/verify")
    public String verifyIndex() {
        return "verify-index";
    }

    @GetMapping("/verify/{uuid}")
    public String verifyByUuid(@PathVariable String uuid, Model model) {
        model.addAttribute("uuid", uuid);
        return "verify-document";
    }
}

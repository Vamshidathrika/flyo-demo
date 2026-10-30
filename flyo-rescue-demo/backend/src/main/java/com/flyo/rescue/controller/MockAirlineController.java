package com.flyo.rescue.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Controller
@RequestMapping("/mock-airline")
public class MockAirlineController {

    private static volatile boolean broken = false;

    public static boolean isBroken() {
        return broken;
    }

    public static void setBroken(boolean val) {
        broken = val;
    }

    @PostMapping("/toggle-break")
    @ResponseBody
    public Map<String, Object> toggleBreak(@RequestParam(required = false) Boolean broken) {
        if (broken != null) {
            MockAirlineController.broken = broken;
        } else {
            MockAirlineController.broken = !MockAirlineController.broken;
        }
        return Map.of(
                "broken", MockAirlineController.broken,
                "status", MockAirlineController.broken ? "BROKEN" : "OPERATIONAL",
                "message", MockAirlineController.broken 
                        ? "Mock airline portal is set to BROKEN (simulating outage / 500 error)"
                        : "Mock airline portal is OPERATIONAL"
        );
    }

    @GetMapping("/break-status")
    @ResponseBody
    public Map<String, Object> getBreakStatus() {
        return Map.of(
                "broken", broken,
                "status", broken ? "BROKEN" : "OPERATIONAL"
        );
    }

    @GetMapping(value = "/search", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public Resource searchPage() {
        return new ClassPathResource("static/mock-airline/search.html");
    }

    @GetMapping(value = "/select", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public Resource selectPage() {
        return new ClassPathResource("static/mock-airline/select.html");
    }

    @GetMapping(value = "/confirm", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirmPage() throws IOException {
        if (broken) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_HTML)
                    .body("""
                        <!DOCTYPE html>
                        <html lang="en">
                        <head>
                            <meta charset="UTF-8">
                            <title>500 Internal Server Error &mdash; Mock Airline Gateway</title>
                        </head>
                        <body style="font-family: -apple-system, BlinkMacSystemFont, sans-serif; background: #fff1f2; color: #9f1239; padding: 3rem; text-align: center;">
                            <div id="portal-error" style="border: 2px solid #e11d48; padding: 2.5rem; border-radius: 12px; max-width: 600px; margin: 0 auto; background: white; box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1);">
                                <div style="font-size: 1.5rem; font-weight: 800; color: #e11d48; margin-bottom: 1rem;">[GATEWAY ERROR]</div>
                                <h1 style="color: #be123c; font-size: 1.5rem; margin-bottom: 0.5rem;">500 Internal Server Error</h1>
                                <p style="font-size: 1.1rem; font-weight: 700; color: #881337;">Mock Airline Reservation Gateway Offline</p>
                                <p style="color: #475569; font-size: 0.9rem; line-height: 1.5;">GDS Connection timed out while confirming ticket issuance. Transaction aborted by carrier host.</p>
                                <div style="margin-top: 1.5rem; padding: 0.5rem; background: #f1f5f9; border-radius: 6px; font-family: monospace; font-size: 0.8rem; color: #64748b;">
                                    ERR_GDS_HOST_UNREACHABLE_TIMEOUT_500
                                </div>
                            </div>
                        </body>
                        </html>
                    """);
        }
        String html = new String(new ClassPathResource("static/mock-airline/confirm.html").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }
}

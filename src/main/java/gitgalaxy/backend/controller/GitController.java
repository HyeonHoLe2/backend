package gitgalaxy.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GitController {

    @GetMapping("/user")
    public ResponseEntity<String> user(@AuthenticationPrincipal OAuth2User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("not logged in");
        }
        return ResponseEntity.ok("ok");
    }
}

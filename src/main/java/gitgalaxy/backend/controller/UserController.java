package gitgalaxy.backend.controller;

import gitgalaxy.backend.entity.AppUser;
import gitgalaxy.backend.model.FavoriteResponse;
import gitgalaxy.backend.model.UserResponse;
import gitgalaxy.backend.repository.AppUserRepository;
import gitgalaxy.backend.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final AppUserRepository appUserRepository;
    private final FavoriteService favoriteService;

    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getUser(@PathVariable Long userId) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found: " + userId));
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @GetMapping("/me/favorites")
    public ResponseEntity<List<FavoriteResponse>> getFavorites(@AuthenticationPrincipal OAuth2User principal) {
        return ResponseEntity.ok(favoriteService.listFavorites(getGithubId(principal)));
    }

    @PostMapping("/me/favorites/{repoId}")
    public ResponseEntity<FavoriteResponse> addFavorite(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable Long repoId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(favoriteService.addFavorite(getGithubId(principal), repoId));
    }

    @DeleteMapping("/me/favorites/{repoId}")
    public ResponseEntity<Void> removeFavorite(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable Long repoId) {
        favoriteService.removeFavorite(getGithubId(principal), repoId);
        return ResponseEntity.noContent().build();
    }

    private Long getGithubId(OAuth2User principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        Object id = principal.getAttribute("id");
        if (id instanceof Integer i) return i.longValue();
        if (id instanceof Long l) return l;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 사용자입니다.");
    }
}

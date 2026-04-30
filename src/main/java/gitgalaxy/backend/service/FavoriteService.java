package gitgalaxy.backend.service;

import gitgalaxy.backend.entity.AppUser;
import gitgalaxy.backend.entity.UserFavorite;
import gitgalaxy.backend.model.FavoriteResponse;
import gitgalaxy.backend.repository.AppUserRepository;
import gitgalaxy.backend.repository.UserFavoriteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final UserFavoriteRepository userFavoriteRepository;
    private final AppUserRepository appUserRepository;

    @Transactional
    public FavoriteResponse addFavorite(Long githubId, Long repoId) {
        AppUser user = findUserByGithubId(githubId);

        if (userFavoriteRepository.existsByUserIdAndRepoId(user.getUserId(), repoId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already in favorites");
        }

        UserFavorite favorite = new UserFavorite();
        favorite.setUserId(user.getUserId());
        favorite.setRepoId(repoId);
        return FavoriteResponse.from(userFavoriteRepository.save(favorite));
    }

    @Transactional
    public void removeFavorite(Long githubId, Long repoId) {
        AppUser user = findUserByGithubId(githubId);
        UserFavorite favorite = userFavoriteRepository
                .findByUserIdAndRepoId(user.getUserId(), repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "favorite not found"));
        userFavoriteRepository.delete(favorite);
    }

    @Transactional(readOnly = true)
    public List<FavoriteResponse> listFavorites(Long githubId) {
        AppUser user = findUserByGithubId(githubId);
        return userFavoriteRepository.findByUserId(user.getUserId())
                .stream()
                .map(FavoriteResponse::from)
                .toList();
    }

    private AppUser findUserByGithubId(Long githubId) {
        return appUserRepository.findByGithubId(githubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }
}

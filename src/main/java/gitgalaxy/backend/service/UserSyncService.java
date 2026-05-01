package gitgalaxy.backend.service;

import gitgalaxy.backend.entity.AppUser;
import gitgalaxy.backend.repository.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserSyncService {

    private final AppUserRepository appUserRepository;

    public UserSyncService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Transactional
    public void syncUser(Long githubId, String login, String profileUrl) {
        AppUser appUser = appUserRepository.findByGithubId(githubId).orElseGet(AppUser::new);
        appUser.setGithubId(githubId);
        appUser.setGithubLogin(login);
        appUser.setProfileUrl(profileUrl);
        appUserRepository.save(appUser);
    }
}

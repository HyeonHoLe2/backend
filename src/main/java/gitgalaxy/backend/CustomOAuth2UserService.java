package gitgalaxy.backend;

import gitgalaxy.backend.entity.AppUser;
import gitgalaxy.backend.repository.AppUserRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final AppUserRepository appUserRepository;

    public CustomOAuth2UserService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest request) {
        OAuth2User oauth2User = super.loadUser(request);

        Long githubId = toLong(oauth2User.getAttribute("id"));
        String login = stringAttr(oauth2User, "login");
        if (githubId == null || login == null) {
            return oauth2User;
        }

        AppUser appUser = appUserRepository.findByGithubId(githubId).orElseGet(AppUser::new);
        appUser.setGithubId(githubId);
        appUser.setGithubLogin(login);


        appUser.setProfileUrl(stringAttr(oauth2User, "avatar_url"));
        appUserRepository.save(appUser);

        return oauth2User;
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stringAttr(OAuth2User user, String key) {
        Object v = user.getAttribute(key);
        return v != null ? v.toString() : null;
    }
}

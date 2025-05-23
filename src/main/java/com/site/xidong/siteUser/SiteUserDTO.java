package com.site.xidong.siteUser;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SiteUserDTO {
    private String username;
    private String email;
    private String nickname;
    private String imageUrl;
}

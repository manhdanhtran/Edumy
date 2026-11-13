package com.edumy.identity.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import com.edumy.identity.entity.UserStatus;
import com.edumy.shared.jpa.CodeEnum;
import com.edumy.shared.security.RoleCode;
import org.junit.jupiter.api.Test;

/** UC-A01 query parameters: blank = no filter, and the accepted codes are exactly the enum codes. */
class AdminUserSearchRequestTest {

    @Test
    void blankParameters_becomeNull_qIsStripped() {
        AdminUserSearchRequest request = new AdminUserSearchRequest("  hung ", " ", "");
        assertThat(request.q()).isEqualTo("hung");
        assertThat(request.status()).isNull();
        assertThat(request.role()).isNull();
        assertThat(new AdminUserSearchRequest(null, null, null).q()).isNull();
    }

    @Test
    void acceptedCodes_areTheEnumCodes() {
        assertThat(AdminUserSearchRequest.STATUS_CODES.split("[|]"))
                .containsExactlyInAnyOrder(codes(UserStatus.values()));
        assertThat(AdminUserSearchRequest.ROLE_CODES.split("[|]"))
                .containsExactlyInAnyOrder(codes(RoleCode.values()));
    }

    private static String[] codes(CodeEnum[] values) {
        return Arrays.stream(values).map(CodeEnum::code).toArray(String[]::new);
    }
}

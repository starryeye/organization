package dev.starryeye.organization.scim;

/** SCIM 2.0 이 규정한 스키마 URN. IdP 가 본문의 이 값을 검증한다. */
public final class ScimSchemas {

    public static final String USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group";
    public static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";
    public static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    public static final String SERVICE_PROVIDER_CONFIG =
            "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";

    private ScimSchemas() {
    }
}

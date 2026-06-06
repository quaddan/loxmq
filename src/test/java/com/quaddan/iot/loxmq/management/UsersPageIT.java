/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.management;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * IT for the {@code /users} HTML page + error paths of
 * {@code /api/v1/users}, {@code /api/v1/groups}, {@code /api/v1/users-snapshot}.
 *
 * <p>Same approach as {@code SchedulesPageIT} : verify page rendering
 * + 503 / 400 error envelopes without a happy-path Miniserver
 * round-trip (which is covered by {@link AdminHappyPathIT}).
 */
@QuarkusIntegrationTest
@DisplayName( "UsersPageIT — /users HTML + /api/v1/users error paths" )
class UsersPageIT
{
    @Test
    @DisplayName( "GET /users — renders HTML page even without RUNNING session" )
    void pageRenders()
    {
        given().when()
               .get( "/users" )
               .then()
               .statusCode( 200 )
               .contentType( containsString( "text/html" ) )
               .body( containsString( "Users" ) )
               .body( containsString( "users-snapshot" ) )    // JS fetch target
               .body( containsString( "Groups" ) );
    }

    @Test
    @DisplayName( "GET /users — page includes mutations UI" )
    void pageIncludesMutationsUI()
    {
        // Verify the JS mutation surface lives in the rendered page :
        // - disableUser function (POST /disable)
        // - addToGroup / removeFromGroup functions
        // - the Groups section block builder
        // - the "Disable" data-action attribute on the user row template
        given().when()
               .get( "/users" )
               .then()
               .statusCode( 200 )
               .body( containsString( "disableUser" ) )
               .body( containsString( "addToGroup" ) )
               .body( containsString( "removeFromGroup" ) )
               .body( containsString( "renderGroupsBlock" ) )
               .body( containsString( "data-action=\"disable\"" ) )
               .body( containsString( "data-action=\"add-group\"" ) )
               .body( containsString( "data-action=\"remove-group\"" ) )
               .body( containsString( "/disable" ) );
    }

    @Test
    @DisplayName( "GET /api/v1/users without RUNNING session → 503" )
    void listUsersReturns503WhenNotRunning()
    {
        given().when()
               .get( "/api/v1/users" )
               .then()
               .statusCode( 503 )
               .body( "status", equalTo( "error" ) )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "GET /api/v1/groups without RUNNING session → 503" )
    void listGroupsReturns503WhenNotRunning()
    {
        given().when()
               .get( "/api/v1/groups" )
               .then()
               .statusCode( 503 )
               .body( "status", equalTo( "error" ) )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "GET /api/v1/users/{uuid} with short uuid → 400 invalid-input" )
    void getUserRejectsShortUuid()
    {
        // Short UUID is rejected client-side by UserService.getUser
        // BEFORE the session check → 400, not 503.
        given().when()
               .get( "/api/v1/users/short" )
               .then()
               .statusCode( 400 )
               .body( "status", equalTo( "error" ) )
               .body( "code", equalTo( "invalid-input" ) );
    }

    @Test
    @DisplayName( "GET /api/v1/users-snapshot without RUNNING session → 503" )
    void snapshotReturns503WhenNotRunning()
    {
        given().when()
               .get( "/api/v1/users-snapshot" )
               .then()
               .statusCode( 503 )
               .body( "status", equalTo( "error" ) );
    }

    // ============================================================
    //  Mutations
    // ============================================================

    @Test
    @DisplayName( "POST /api/v1/users/{uuid}/disable with short uuid → 400 invalid-input" )
    void disableRejectsShortUuid()
    {
        given().when()
               .post( "/api/v1/users/short/disable" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users/{uuid}/disable without RUNNING session → 503" )
    void disableReturns503WhenNotRunning()
    {
        given().when()
               .post( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/disable" )
               .then()
               .statusCode( 503 )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users/{u}/groups/{g} without RUNNING session → 503" )
    void assignReturns503WhenNotRunning()
    {
        given().when()
               .post( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/groups/089396d4-0207-0119-1900000000000000" )
               .then()
               .statusCode( 503 );
    }

    @Test
    @DisplayName( "DELETE /api/v1/users/{u}/groups/{g} without RUNNING session → 503" )
    void removeReturns503WhenNotRunning()
    {
        given().when()
               .delete( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/groups/089396d4-0207-0119-1900000000000000" )
               .then()
               .statusCode( 503 );
    }

    // ============================================================
    //  CRUD complet
    // ============================================================

    @Test
    @DisplayName( "GET /users — page includes CRUD UI" )
    void pageIncludesCrudUI()
    {
        // Verify the JS CRUD surface lives in the rendered page :
        // - + New user button, Delete data-action, edit form scaffolding
        // - create-modal element + create-form / edit-form / EDIT_FIELDS
        given().when()
               .get( "/users" )
               .then()
               .statusCode( 200 )
               .body( containsString( "open-create" ) )
               .body( containsString( "+ New user" ) )
               .body( containsString( "create-modal" ) )
               .body( containsString( "data-action=\"delete\"" ) )
               .body( containsString( "EDIT_FIELDS" ) )
               .body( containsString( "buildEditPatch" ) )
               .body( containsString( "data-tab=\"edit\"" ) )
               .body( containsString( "data-tab=\"groups\"" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users with no body → 400 invalid-input (name required)" )
    void createRejectsEmptyBody()
    {
        given().contentType( ContentType.JSON )
               .body( "{}" )
               .when()
               .post( "/api/v1/users" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) )
               .body( "message", containsString( "Name" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users with blank name → 400 invalid-input" )
    void createRejectsBlankName()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"name\":\"   \"}" )
               .when()
               .post( "/api/v1/users" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users with valid name (no session) → 503 session-not-running" )
    void createReturns503WhenNotRunning()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"name\":\"Alice\"}" )
               .when()
               .post( "/api/v1/users" )
               .then()
               .statusCode( 503 )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "DELETE /api/v1/users/{uuid} with short uuid → 400 invalid-input" )
    void deleteRejectsShortUuid()
    {
        given().when()
               .delete( "/api/v1/users/short" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) );
    }

    @Test
    @DisplayName( "DELETE /api/v1/users/{uuid} without RUNNING session → 503" )
    void deleteReturns503WhenNotRunning()
    {
        given().when()
               .delete( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000" )
               .then()
               .statusCode( 503 )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "PUT /api/v1/users/{uuid} with short uuid → 400 invalid-input" )
    void editRejectsShortUuid()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"desc\":\"hello\"}" )
               .when()
               .put( "/api/v1/users/short" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) );
    }

    @Test
    @DisplayName( "PUT /api/v1/users/{uuid} without RUNNING session → 503" )
    void editReturns503WhenNotRunning()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"desc\":\"hello\"}" )
               .when()
               .put( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000" )
               .then()
               .statusCode( 503 )
               .body( "code", equalTo( "session-not-running" ) );
    }

    // ============================================================
    //  Auth ops
    // ============================================================

    @Test
    @DisplayName( "GET /users — page includes Auth tab" )
    void pageIncludesAuthTab()
    {
        given().when()
               .get( "/users" )
               .then()
               .statusCode( 200 )
               .body( containsString( "data-tab=\"auth\"" ) )
               .body( containsString( "auth-pw-save" ) )
               .body( containsString( "auth-visu-save" ) )
               .body( containsString( "auth-code-save" ) )
               .body( containsString( "submitAuthOp" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users/{u}/auth/password with empty body → 400 invalid-input" )
    void updatePasswordRejectsEmpty()
    {
        given().contentType( ContentType.JSON )
               .body( "{}" )
               .when()
               .post( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/auth/password" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users/{u}/auth/password short uuid → 400" )
    void updatePasswordRejectsShortUuid()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"value\":\"newpw\"}" )
               .when()
               .post( "/api/v1/users/short/auth/password" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users/{u}/auth/password without session → 503" )
    void updatePasswordReturns503WhenNotRunning()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"value\":\"newpw\"}" )
               .when()
               .post( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/auth/password" )
               .then()
               .statusCode( 503 )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users/{u}/auth/visu-password without session → 503" )
    void updateVisuPasswordReturns503WhenNotRunning()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"value\":\"1234\"}" )
               .when()
               .post( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/auth/visu-password" )
               .then()
               .statusCode( 503 );
    }

    @Test
    @DisplayName( "POST /api/v1/users/{u}/auth/access-code rejects non-numeric → 400" )
    void updateAccessCodeRejectsNonNumeric()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"value\":\"abcd\"}" )
               .when()
               .post( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/auth/access-code" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) )
               .body( "message", containsString( "digits" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users/{u}/auth/access-code rejects too-short → 400" )
    void updateAccessCodeRejectsTooShort()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"value\":\"12\"}" )
               .when()
               .post( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/auth/access-code" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users/{u}/auth/access-code 4-digit code without session → 503" )
    void updateAccessCodeReturns503WhenNotRunning()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"value\":\"1234\"}" )
               .when()
               .post( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/auth/access-code" )
               .then()
               .statusCode( 503 );
    }

    // ============================================================
    //  Metadata helpers
    // ============================================================

    @Test
    @DisplayName( "GET /api/v1/user-metadata/custom-fields without session → 503" )
    void customFieldsReturns503WhenNotRunning()
    {
        given().when()
               .get( "/api/v1/user-metadata/custom-fields" )
               .then()
               .statusCode( 503 )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "GET /api/v1/user-metadata/property-options without session → 503" )
    void propertyOptionsReturns503WhenNotRunning()
    {
        given().when()
               .get( "/api/v1/user-metadata/property-options" )
               .then()
               .statusCode( 503 );
    }

    @Test
    @DisplayName( "GET /api/v1/user-metadata/check-userid/{id} without session → 503" )
    void checkUserIdReturns503WhenNotRunning()
    {
        given().when()
               .get( "/api/v1/user-metadata/check-userid/foobar" )
               .then()
               .statusCode( 503 );
    }

    @Test
    @DisplayName( "GET /api/v1/users/{uuid}/control-permissions short uuid → 400" )
    void controlPermissionsRejectsShortUuid()
    {
        given().when()
               .get( "/api/v1/users/short/control-permissions" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) );
    }

    @Test
    @DisplayName( "GET /api/v1/users/{uuid}/control-permissions without session → 503" )
    void controlPermissionsReturns503WhenNotRunning()
    {
        given().when()
               .get( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/control-permissions" )
               .then()
               .statusCode( 503 );
    }

    @Test
    @DisplayName( "GET /users — page wires the metadata fetch" )
    void pageWiresMetadataFetch()
    {
        given().when()
               .get( "/users" )
               .then()
               .statusCode( 200 )
               .body( containsString( "refreshMetadata" ) )
               .body( containsString( "customFieldLabels" ) )
               .body( containsString( "/user-metadata/custom-fields" ) );
    }

    // ============================================================
    //  NFC ops
    // ============================================================

    @Test
    @DisplayName( "GET /users — page includes NFC tab" )
    void pageIncludesNfcTab()
    {
        given().when()
               .get( "/users" )
               .then()
               .statusCode( 200 )
               .body( containsString( "data-tab=\"nfc\"" ) )
               .body( containsString( "nfc-tags-list" ) )
               .body( containsString( "nfc-discover" ) )
               .body( containsString( "nfc-add" ) )
               .body( containsString( "renderNfcList" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/nfc/discover without session → 503" )
    void nfcDiscoverReturns503WhenNotRunning()
    {
        given().when()
               .post( "/api/v1/nfc/discover" )
               .then()
               .statusCode( 503 )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users/{u}/nfc with empty body → 400 invalid-input" )
    void nfcAddRejectsEmptyBody()
    {
        given().contentType( ContentType.JSON )
               .body( "{}" )
               .when()
               .post( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/nfc" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users/{u}/nfc non-hex tag → 400" )
    void nfcAddRejectsNonHexTag()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"tagId\":\"XYZ\",\"name\":\"Test\"}" )
               .when()
               .post( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/nfc" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) )
               .body( "message", containsString( "hex" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users/{u}/nfc short uuid → 400" )
    void nfcAddRejectsShortUuid()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"tagId\":\"AABBCCDD\"}" )
               .when()
               .post( "/api/v1/users/short/nfc" )
               .then()
               .statusCode( 400 );
    }

    @Test
    @DisplayName( "POST /api/v1/users/{u}/nfc valid tag without session → 503" )
    void nfcAddReturns503WhenNotRunning()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"tagId\":\"AABBCCDD\",\"name\":\"Test\"}" )
               .when()
               .post( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/nfc" )
               .then()
               .statusCode( 503 )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "DELETE /api/v1/users/{u}/nfc/{tag} non-hex → 400" )
    void nfcRemoveRejectsNonHexTag()
    {
        given().when()
               .delete( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/nfc/XYZ" )
               .then()
               .statusCode( 400 );
    }

    @Test
    @DisplayName( "DELETE /api/v1/users/{u}/nfc/{tag} valid without session → 503" )
    void nfcRemoveReturns503WhenNotRunning()
    {
        given().when()
               .delete( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/nfc/AABBCCDD" )
               .then()
               .statusCode( 503 );
    }

    // ============================================================
    //  Admin override
    // ============================================================

    @Test
    @DisplayName( "GET /users — page wires the admin-override force flow" )
    void pageWiresAdminOverride()
    {
        given().when()
               .get( "/users" )
               .then()
               .statusCode( 200 )
               .body( containsString( "data-admin" ) )
               .body( containsString( "force=true" ) )
               .body( containsString( "admin override" ) )
               .body( containsString( "retype the user name" ) );
    }

    @Test
    @DisplayName( "DELETE /api/v1/users/{uuid}?force=true short uuid → 400" )
    void deleteWithForceRejectsShortUuid()
    {
        given().queryParam( "force", "true" )
               .when()
               .delete( "/api/v1/users/short" )
               .then()
               .statusCode( 400 );
    }

    @Test
    @DisplayName( "DELETE /api/v1/users/{uuid}?force=true without session → 503" )
    void deleteWithForceReturns503WhenNotRunning()
    {
        given().queryParam( "force", "true" )
               .when()
               .delete( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000" )
               .then()
               .statusCode( 503 )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/users/{uuid}/disable?force=true without session → 503" )
    void disableWithForceReturns503WhenNotRunning()
    {
        given().queryParam( "force", "true" )
               .when()
               .post( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000/disable" )
               .then()
               .statusCode( 503 )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "DELETE force flag defaults to false when omitted" )
    void deleteDefaultsToNoForce()
    {
        // Without ?force, the endpoint still works (just hits the
        // session-not-running guard). This is a regression check that
        // the @DefaultValue("false") on the query param doesn't make
        // the endpoint reject the call entirely.
        given().when()
               .delete( "/api/v1/users/0a5fa72f-018b-0050-1900000000000000" )
               .then()
               .statusCode( 503 );
    }

    // ============================================================
    //  Group CRUD
    // ============================================================

    @Test
    @DisplayName( "GET /users — page wires Group CRUD UI" )
    void pageIncludesGroupCrudUI()
    {
        given().when()
               .get( "/users" )
               .then()
               .statusCode( 200 )
               .body( containsString( "open-create-group" ) )
               .body( containsString( "+ New group" ) )
               .body( containsString( "group-modal" ) )
               .body( containsString( "data-action=\"edit-group\"" ) )
               .body( containsString( "data-action=\"delete-group\"" ) )
               .body( containsString( "openGroupModal" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/groups with no body → 400 invalid-input" )
    void createGroupRejectsEmptyBody()
    {
        given().contentType( ContentType.JSON )
               .body( "{}" )
               .when()
               .post( "/api/v1/groups" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) )
               .body( "message", containsString( "Name" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/groups with blank name → 400 invalid-input" )
    void createGroupRejectsBlankName()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"name\":\"   \"}" )
               .when()
               .post( "/api/v1/groups" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) );
    }

    @Test
    @DisplayName( "POST /api/v1/groups valid name without session → 503" )
    void createGroupReturns503WhenNotRunning()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"name\":\"Visitors\"}" )
               .when()
               .post( "/api/v1/groups" )
               .then()
               .statusCode( 503 )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "PUT /api/v1/groups/{uuid} short uuid → 400 invalid-input" )
    void editGroupRejectsShortUuid()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"name\":\"X\"}" )
               .when()
               .put( "/api/v1/groups/short" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) );
    }

    @Test
    @DisplayName( "PUT /api/v1/groups/{uuid} without session → 503" )
    void editGroupReturns503WhenNotRunning()
    {
        given().contentType( ContentType.JSON )
               .body( "{\"description\":\"updated\"}" )
               .when()
               .put( "/api/v1/groups/089396d4-0207-0119-1900000000000000" )
               .then()
               .statusCode( 503 )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "DELETE /api/v1/groups/{uuid} short uuid → 400" )
    void deleteGroupRejectsShortUuid()
    {
        given().when()
               .delete( "/api/v1/groups/short" )
               .then()
               .statusCode( 400 )
               .body( "code", equalTo( "invalid-input" ) );
    }

    @Test
    @DisplayName( "DELETE /api/v1/groups/{uuid} without session → 503" )
    void deleteGroupReturns503WhenNotRunning()
    {
        given().when()
               .delete( "/api/v1/groups/089396d4-0207-0119-1900000000000000" )
               .then()
               .statusCode( 503 )
               .body( "code", equalTo( "session-not-running" ) );
    }

    @Test
    @DisplayName( "GET /users — page wires AbortController + pagehide" )
    void pageWiresAbortController()
    {
        given().when()
               .get( "/users" )
               .then()
               .statusCode( 200 )
               .body( containsString( "pageAbortController" ) )
               .body( containsString( "isAborted" ) )
               .body( containsString( "pagehide" ) );
    }
}

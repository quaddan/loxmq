/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link AdminCommandResponses#decorateCodeError} so the hint stays
 * context-aware. An earlier implementation blasted the
 * "Check calMode↔calModeAttr" hint on every 400 regardless of which
 * command failed, surfacing misleading schedule terminology on user /
 * group operations.
 */
class AdminCommandResponsesTest
{
    // ------------------------------------------------------------------
    //  400 routing
    // ------------------------------------------------------------------

    @Test
    @DisplayName( "400 + addschedule → keeps calMode hint" )
    void schedule400KeepsCalModeHint()
    {
        String msg = AdminCommandResponses.decorateCodeError(
                "400", "addschedule/foo", "" );
        assertThat( msg ).contains( "calMode" );
        assertThat( msg ).contains( "WEEKDAY needs 3" );
    }

    @Test
    @DisplayName( "400 + updateschedule → keeps calMode hint" )
    void updateSchedule400KeepsCalModeHint()
    {
        String msg = AdminCommandResponses.decorateCodeError(
                "400", "updateschedule/uuid/foo", "" );
        assertThat( msg ).contains( "calMode" );
    }

    @Test
    @DisplayName( "400 + assignusertogroup + 'already assigned' → friendly no-op hint" )
    void assignAlreadyAssignedIsFriendly()
    {
        String msg = AdminCommandResponses.decorateCodeError(
                "400",
                "assignusertogroup/userUuid/groupUuid",
                "already assigned" );
        assertThat( msg ).contains( "idempotent" );
        assertThat( msg ).doesNotContain( "calMode" );
        assertThat( msg ).doesNotContain( "WEEKDAY" );
    }

    @Test
    @DisplayName( "400 + non-schedule command → generic hint, no calMode reference" )
    void userMutation400UsesGenericHint()
    {
        String msg = AdminCommandResponses.decorateCodeError(
                "400", "addoredituser/...", "" );
        assertThat( msg ).contains( "malformed input" );
        assertThat( msg ).doesNotContain( "calMode" );
        assertThat( msg ).doesNotContain( "WEEKDAY" );
        assertThat( msg ).doesNotContain( "SPECIFIC_TIMESPAN" );
    }

    @Test
    @DisplayName( "400 + addoreditusergroup → generic hint, no calMode reference" )
    void groupMutation400UsesGenericHint()
    {
        String msg = AdminCommandResponses.decorateCodeError(
                "400", "addoreditusergroup/...", "" );
        assertThat( msg ).contains( "malformed input" );
        assertThat( msg ).doesNotContain( "calMode" );
    }

    @Test
    @DisplayName( "400 'already assigned' is case-insensitive" )
    void alreadyAssignedCaseInsensitive()
    {
        String msg = AdminCommandResponses.decorateCodeError(
                "400", "assignusertogroup/u/g", "ALREADY ASSIGNED" );
        assertThat( msg ).contains( "idempotent" );
    }

    // ------------------------------------------------------------------
    //  Other codes — sanity check
    // ------------------------------------------------------------------

    @Test
    @DisplayName( "401 / 403 → permission hint preserved" )
    void permissionHintsPreserved()
    {
        String m401 = AdminCommandResponses.decorateCodeError( "401", "getuserlist2", "" );
        String m403 = AdminCommandResponses.decorateCodeError( "403", "getuserlist2", "" );
        assertThat( m401 ).contains( "Required Rights" );
        assertThat( m403 ).contains( "Required Rights" );
    }

    @Test
    @DisplayName( "404 → entity-gone hint preserved" )
    void notFoundHintPreserved()
    {
        String msg = AdminCommandResponses.decorateCodeError( "404", "deletegroup/uuid", "" );
        assertThat( msg ).contains( "entity not found" );
    }

    @Test
    @DisplayName( "unknown Code → no hint, just the prefix" )
    void unknownCodeGetsNoHint()
    {
        String msg = AdminCommandResponses.decorateCodeError( "500", "foo", "internal" );
        assertThat( msg ).startsWith( "Miniserver rejected foo : Code=500" );
        assertThat( msg ).contains( "value=internal" );
    }

    @Test
    @DisplayName( "envelope shape preserved : 'Miniserver rejected <cmd> : Code=<code> [value=<v>] [hint]'" )
    void envelopeShapePreserved()
    {
        String msg = AdminCommandResponses.decorateCodeError(
                "400", "addschedule/foo", "details" );
        assertThat( msg ).startsWith( "Miniserver rejected addschedule/foo : Code=400" );
        assertThat( msg ).contains( "value=details" );
    }
}

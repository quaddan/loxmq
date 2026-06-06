/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.util;

import com.quaddan.iot.loxmq.miniserver.session.SessionOrchestrator;
import com.quaddan.iot.loxmq.management.SchedulesResource;
import com.quaddan.iot.loxmq.miniserver.admin.AddNfcTagRequest;
import com.quaddan.iot.loxmq.miniserver.admin.CalendarMode;
import com.quaddan.iot.loxmq.miniserver.admin.CreatedGroup;
import com.quaddan.iot.loxmq.miniserver.admin.CreatedUser;
import com.quaddan.iot.loxmq.miniserver.admin.EditGroupRequest;
import com.quaddan.iot.loxmq.miniserver.admin.EditUserRequest;
import com.quaddan.iot.loxmq.miniserver.admin.ScheduleEntry;
import com.quaddan.iot.loxmq.miniserver.admin.UpdatePasswordRequest;
import com.quaddan.iot.loxmq.miniserver.admin.User;
import com.quaddan.iot.loxmq.miniserver.admin.UserDetail;
import com.quaddan.iot.loxmq.miniserver.admin.UserGroup;
import com.quaddan.iot.loxmq.miniserver.bootstrap.BootstrapStatus;
import com.quaddan.iot.loxmq.miniserver.bootstrap.BootstrapTracker;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverApiConnectorSetCommand;
import com.quaddan.iot.loxmq.miniserver.command.MiniserverCommand;
import com.quaddan.iot.loxmq.miniserver.connection.ConnectionMode;
import com.quaddan.iot.loxmq.miniserver.http.CfgApiResponse;
import com.quaddan.iot.loxmq.miniserver.http.CfgApiValue;
import com.quaddan.iot.loxmq.miniserver.http.KeyAndSaltResponse;
import com.quaddan.iot.loxmq.miniserver.http.PublicKeyResponse;
import com.quaddan.iot.loxmq.miniserver.identity.HttpsStatus;
import com.quaddan.iot.loxmq.miniserver.identity.MiniserverIdentity;
import com.quaddan.iot.loxmq.miniserver.message.DecodedMessages;
import com.quaddan.iot.loxmq.miniserver.session.LoxApp3MetadataResolver;
import com.quaddan.iot.loxmq.miniserver.session.MiniserverToken;
import com.quaddan.iot.loxmq.miniserver.session.SessionState;
import com.quaddan.iot.loxmq.miniserver.session.TokenValue;
import io.quarkus.qute.TemplateData;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Central registration of all Jackson-deserialised DTOs for native-image
 * builds. GraalVM strips the class metadata (record components,
 * constructors, accessors) of classes it can't statically prove are
 * reflectively used; Jackson's {@code ObjectMapper.readValue(json,
 * Foo.class)} relies precisely on that metadata and fails at runtime
 * with:
 *
 * <pre>
 *   Cannot construct instance of `…CfgApiResponse`: cannot deserialize
 *   from Object value (no delegate- or property-based Creator):
 *   this appears to be a native image, in which case you may need to
 *   configure reflection for the class that is to be deserialized
 * </pre>
 *
 * <p>{@link RegisterForReflection} at the {@code targets} level lists
 * every class (including nested records) that must keep its reflection
 * metadata. Centralised here rather than scattered across each DTO so:
 * <ul>
 *   <li>the DTO files stay focused on their schema + javadoc;</li>
 *   <li>adding a new DTO requires touching exactly one file (this one)
 *       — easy to grep, easy to review;</li>
 *   <li>the list is the single source of truth for "what does native
 *       need to reflect on" — useful when debugging future native
 *       deserialisation surprises.</li>
 * </ul>
 *
 * <h3>What's covered</h3>
 * <ul>
 *   <li>Bootstrap responses ({@code jdev/cfg/apiKey},
 *       {@code jdev/sys/getPublicKey}, {@code jdev/sys/getkey2/...}).</li>
 *   <li>Inbound MQTT command payloads on {@code …/command} and
 *       {@code …/api}.</li>
 * </ul>
 *
 * <h3>What's NOT covered (intentionally)</h3>
 * {@link SessionOrchestrator}
 * uses {@code ObjectMapper.readTree()} +
 * {@link com.fasterxml.jackson.databind.JsonNode#path} — no typed
 * deserialisation, no reflection needed, the responses are walked
 * generically.
 *
 * <p>{@link MiniserverIdentity}
 * is built via record constructor from values pulled by
 * {@link CfgApiValue} — it is read, never deserialised — so it does
 * not need to be registered.
 *
 * @see <a href="https://quarkus.io/guides/writing-native-applications-tips#registerForReflection">
 *      Quarkus — Writing native applications, §RegisterForReflection</a>
 */
// ─────────────────────────────────────────────────────────────────────
//  Qute @TemplateData — generates BUILD-time ValueResolvers so the
//  dashboard template can call .name() on the enums + accessor methods
//  on the records WITHOUT runtime reflection. @RegisterForReflection
//  alone is not enough: Qute's resolver chain in native mode prefers
//  generated ValueResolvers and falls back to "property not found" if
//  none exists, even when the underlying class has reflection metadata.
// ─────────────────────────────────────────────────────────────────────
@TemplateData( target = BootstrapStatus.class )
@TemplateData( target = BootstrapTracker.class )
@TemplateData( target = ConnectionMode.class )
@TemplateData( target = HttpsStatus.class )
@TemplateData( target = MiniserverIdentity.class )
@TemplateData( target = MiniserverToken.class )
@TemplateData( target = SessionState.class )
@RegisterForReflection( targets = {
        // Bootstrap — jdev/cfg/apiKey response chain
        CfgApiResponse.class,
        CfgApiResponse.LL.class,
        CfgApiValue.class,

        // Bootstrap — jdev/sys/getPublicKey response chain
        PublicKeyResponse.class,
        PublicKeyResponse.LL.class,

        // Bootstrap — jdev/sys/getkey2/{user} response chain
        KeyAndSaltResponse.class,
        KeyAndSaltResponse.LL.class,
        KeyAndSaltResponse.Value.class,

        // Session handshake — jdev/sys/getjwt/... and refreshjwt responses.
        // SessionOrchestrator uses jsonMapper.treeToValue() (not readValue),
        // which my first reflection-pass grep "readValue.*\.class" missed.
        // Caught the second native boot (Boot 3/3 failed at token parse).
        TokenValue.class,

        // Inbound MQTT command payloads
        MiniserverCommand.class,
        MiniserverApiConnectorSetCommand.class,

        // Outbound state-event payloads — serialised by StatesPublisher in
        // BATCH mode via jsonMapper.writeValueAsBytes(event.<type>States()).
        // Native error: "No serializer found for class … and no properties
        // discovered to create BeanSerializer". DEDUPLICATED record types —
        // each {Header, *State, *States} needs full reflection metadata so
        // Jackson can introspect record components at write-time.
        DecodedMessages.Header.class,
        DecodedMessages.ValueState.class,
        DecodedMessages.ValueStates.class,
        DecodedMessages.TextState.class,
        DecodedMessages.TextStates.class,
        DecodedMessages.DayTimerState.class,
        DecodedMessages.DayTimerStates.class,
        DecodedMessages.WeatherState.class,
        DecodedMessages.WeatherStates.class,

        // Dashboard / Qute template reachables — the @CheckedTemplate at
        // DashboardResource passes these as parameters, and the template
        // calls .name() on the enums + accessor methods on the records.
        // Native error: "Property 'name' not found on the base object
        // ConnectionMode" / similar. Both the value-type records AND the
        // enums need full reflection metadata (constructors, accessors,
        // Enum.name(), Enum.values()).
        BootstrapStatus.class,
        BootstrapTracker.class,
        ConnectionMode.class,
        HttpsStatus.class,
        MiniserverIdentity.class,
        MiniserverToken.class,
        SessionState.class,

        // /states page — LoxApp3MetadataResolver.Topology is
        // serialised manually via jsonMapper.writeValueAsString(topology)
        // in LiveStatesResource.java:72 to embed the rooms/cats/controls
        // snapshot in the rendered HTML for client-side cascade dropdowns.
        // Without these targets, native strips the record components and
        // Jackson falls back to BeanSerializer with no properties → the
        // page silently degrades to "empty topology JSON" → dropdowns are
        // empty in prod. JVM tests pass since the JVM keeps record reflection
        // by default — only the WARN in the native log signals the issue.
        LoxApp3MetadataResolver.Topology.class,
        LoxApp3MetadataResolver.ControlInfo.class,

        // /api/v1/schedules — operating-mode calendar CRUD.
        // ScheduleEntry serialised in the response, ScheduleCreateRequest in
        // the POST/PUT body. CalendarMode is an enum — include it too so
        // Jackson can instantiate via name() / fromCode() in native mode.
        // Spec: docs/loxone/OperatingModeSchedule.pdf V14.4.
        ScheduleEntry.class,
        SchedulesResource.ScheduleCreateRequest.class,
        CalendarMode.class,

        // /api/v1/users + /api/v1/groups — Miniserver user management
        // surface. Records are serialised in the REST response by
        // Quarkus/Jackson; EditUserRequest is deserialised from the
        // PUT/POST body. UserDetail contains a List<String> in addition
        // to primitive fields → register the class but List<T> does not
        // require a dedicated entry.
        // Spec: docs/loxone/1700_Usermanagement.pdf V17.
        User.class,
        UserDetail.class,
        UserGroup.class,
        EditUserRequest.class,
        CreatedUser.class,
        // Auth ops — single-field body for password / visu-password /
        // access-code updates.
        UpdatePasswordRequest.class,
        // NFC ops — body for adding a tag with an optional friendly name.
        AddNfcTagRequest.class,
        // Group CRUD — body for create/edit, reply for create.
        // Same pattern as EditUserRequest / CreatedUser.
        EditGroupRequest.class,
        CreatedGroup.class
} )
public final class NativeReflectionConfig
{
    private NativeReflectionConfig() { }
}

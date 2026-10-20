package com.keralarescue.backend.data;

import com.keralarescue.backend.ai.TriageResult;
import com.keralarescue.backend.api.AiInsight;
import com.keralarescue.backend.api.EmergencyRequest;
import com.keralarescue.backend.api.EmergencyView;
import com.keralarescue.backend.api.StatusUpdateRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC persistence for emergency requests, dispatch history, helpers, and messages. */
@Repository
public class EmergencyRepository {
    private final JdbcTemplate jdbc;
    private static final SecureRandom RANDOM = new SecureRandom();

    public EmergencyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record ImageData(byte[] bytes, String contentType) { }
    public record HelperView(String helperId, String name, String phone, String createdAt, Double latitude, Double longitude, String locationUpdatedAt) { }
    public record DeviceToken(String token, String role, String refId) { }
    public record MessageView(long messageId, String emergencyId, String senderRole, String senderName, String message, String createdAt) { }
    public record RescueSummary(int openRequests, int peopleNeedingHelp) { }
    public record RescueAnalytics(double averageResponseSeconds) { }
    public record OpenPoint(String id, Double latitude, Double longitude, String phone, Instant createdAt) { }
    public record HelperLoad(String helperId, String name, Double latitude, Double longitude, Instant locationUpdatedAt, int active) { }

    private final RowMapper<EmergencyView> emergencyMapper = (rs, row) -> mapEmergency(rs);

    private EmergencyView mapEmergency(ResultSet rs) throws SQLException {
        Timestamp assigned = rs.getTimestamp("helper_assigned_at");
        Timestamp arrived = rs.getTimestamp("helper_arrived_at");
        return new EmergencyView(rs.getString("emergency_id"), rs.getString("tracking_token"),
                rs.getString("emergency_type"), rs.getString("priority"), rs.getString("status"),
                rs.getString("location"), rs.getString("description"), rs.getString("phone"),
                rs.getString("assigned_team"), nullableDouble(rs, "latitude"), nullableDouble(rs, "longitude"),
                rs.getBoolean("critical"), rs.getBoolean("location_unavailable"), rs.getInt("people_needing_help"),
                rs.getInt("injured_count"), rs.getString("condition_details"), rs.getString("image_content_type"),
                rs.getBytes("image_data") != null, rs.getString("estimated_arrival"),
                assigned == null ? null : assigned.toInstant().toString(),
                arrived == null ? null : arrived.toInstant().toString(),
                rs.getTimestamp("created_at").toInstant().toString(), rs.getTimestamp("updated_at").toInstant().toString(), mapAi(rs));
    }

    private static AiInsight mapAi(ResultSet rs) throws SQLException {
        String source = rs.getString("ai_source");
        if (source == null) return null;
        String flags = rs.getString("ai_flags");
        Timestamp at = rs.getTimestamp("ai_updated_at");
        return new AiInsight(rs.getString("ai_priority"), rs.getString("ai_type"), rs.getString("ai_summary"), rs.getString("ai_translation"),
                flags == null || flags.isBlank() ? List.of() : Arrays.asList(flags.split(",")), nullableDouble(rs, "ai_confidence"), source,
                rs.getString("ai_duplicate_of"), at == null ? null : at.toInstant().toString());
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column); return rs.wasNull() ? null : value;
    }

    private static final String VIEW_COLUMNS = "emergency_id,tracking_token,emergency_type,priority,status,location,description,phone,assigned_team,latitude,longitude,critical,location_unavailable,people_needing_help,injured_count,condition_details,image_data,image_content_type,estimated_arrival,helper_assigned_at,helper_arrived_at,created_at,updated_at,ai_priority,ai_type,ai_summary,ai_translation,ai_flags,ai_confidence,ai_source,ai_duplicate_of,ai_updated_at";

    public Optional<EmergencyView> findById(String id) { return one("SELECT " + VIEW_COLUMNS + " FROM emergencies WHERE emergency_id=?", id); }
    public Optional<EmergencyView> findHistoryById(String id) { return one("SELECT " + VIEW_COLUMNS + " FROM emergency_history WHERE emergency_id=?", id); }
    private Optional<EmergencyView> one(String sql, Object arg) { return jdbc.query(sql, emergencyMapper, arg).stream().findFirst(); }

    @Transactional
    public EmergencyView saveIfNew(EmergencyRequest r, String id, byte[] image) {
        String type = value(r.emergencyType, "OTHER").toUpperCase();
        String priority = value(r.priority, Boolean.TRUE.equals(r.critical) ? "CRITICAL" : "HIGH").toUpperCase();
        String status = value(r.status, "SOS SENT").toUpperCase();
        int people = Math.max(1, r.peopleNeedingHelp == null ? 1 : r.peopleNeedingHelp);
        int injured = Math.max(0, r.injuredCount == null ? 0 : r.injuredCount);
        jdbc.update("INSERT INTO emergencies (emergency_id,tracking_token,emergency_type,priority,status,location,description,phone,assigned_team,latitude,longitude,critical,location_unavailable,people_needing_help,injured_count,condition_details,image_data,image_content_type,estimated_arrival) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, r.trackingToken, type, priority, status, r.location, r.description, r.phone, r.assignedTeam,
                r.latitude, r.longitude, r.critical != null ? r.critical : "CRITICAL".equals(priority),
                Boolean.TRUE.equals(r.locationUnavailable), people, injured, value(r.conditionDetails, ""), new SqlParameterValue(Types.VARBINARY, image),
                image == null ? null : value(r.imageContentType, "image/jpeg"), null);
        return findById(id).orElseThrow();
    }

    public List<EmergencyView> findRecent() { return jdbc.query("SELECT " + VIEW_COLUMNS + " FROM emergencies ORDER BY created_at DESC", emergencyMapper); }
    public List<EmergencyView> findAssignedTo(String helper) { return jdbc.query("SELECT " + VIEW_COLUMNS + " FROM emergencies WHERE LOWER(COALESCE(assigned_team,''))=LOWER(?) ORDER BY created_at DESC", emergencyMapper, helper); }
    public List<EmergencyView> findHistory() { return jdbc.query("SELECT " + VIEW_COLUMNS + " FROM emergency_history ORDER BY created_at DESC", emergencyMapper); }
    public List<EmergencyView> findHistoryByToken(String token) { return jdbc.query("SELECT " + VIEW_COLUMNS + " FROM emergency_history WHERE tracking_token=? ORDER BY created_at DESC", emergencyMapper, token); }

    @Transactional
    public Optional<EmergencyView> updateStatus(String id, StatusUpdateRequest u) {
        Optional<EmergencyView> before = findById(id);
        if (before.isEmpty()) return Optional.empty();
        String status = u.status == null ? before.get().status() : u.status.trim().toUpperCase();
        String priority = u.priority == null ? before.get().priority() : u.priority.trim().toUpperCase();
        String team = u.unassignHelper ? null : (u.assignedTeam == null ? before.get().assignedTeam() : blankToNull(u.assignedTeam));
        String eta = u.estimatedArrival == null ? before.get().estimatedArrival() : blankToNull(u.estimatedArrival);
        jdbc.update("UPDATE emergencies SET status=?,priority=?,assigned_team=?,estimated_arrival=?,helper_assigned_at=CASE WHEN CAST(? AS VARCHAR(200)) IS NOT NULL AND assigned_team IS NULL THEN CURRENT_TIMESTAMP WHEN CAST(? AS VARCHAR(200)) IS NULL THEN NULL ELSE helper_assigned_at END,helper_arrived_at=CASE WHEN ?='HELPER ARRIVED' THEN COALESCE(helper_arrived_at,CURRENT_TIMESTAMP) ELSE helper_arrived_at END,updated_at=CURRENT_TIMESTAMP WHERE emergency_id=?",
                status, priority, team, eta, team, team, status, id);
        if (isTerminal(status)) moveToHistory(id);
        return findById(id).or(() -> findHistoryById(id));
    }

    private static boolean isTerminal(String status) { return List.of("RESOLVED", "CANCELLED", "COMPLETED", "CLOSED").contains(status); }

    @Transactional
    private void moveToHistory(String id) {
        jdbc.update("INSERT INTO emergency_history SELECT * FROM emergencies WHERE emergency_id=? AND NOT EXISTS (SELECT 1 FROM emergency_history WHERE emergency_id=?)", id, id);
        jdbc.update("DELETE FROM emergencies WHERE emergency_id=?", id);
    }

    @Transactional
    public int archiveExpired() {
        List<String> ids = jdbc.query("SELECT emergency_id FROM emergencies WHERE created_at <= ?", (rs, n) -> rs.getString(1), Timestamp.from(Instant.now().minus(Duration.ofHours(1))));
        ids.forEach(this::moveToHistory);
        jdbc.update("DELETE FROM device_tokens WHERE role='CITIZEN' AND created_at <= ?", Timestamp.from(Instant.now().minus(Duration.ofDays(3))));
        return ids.size();
    }

    public boolean deleteHistory(String id) {
        jdbc.update("DELETE FROM emergency_messages WHERE emergency_id=?", id);
        return jdbc.update("DELETE FROM emergency_history WHERE emergency_id=?", id) > 0;
    }
    public Optional<ImageData> findImage(String id) {
        return jdbc.query("SELECT image_data,image_content_type FROM emergencies WHERE emergency_id=? UNION ALL SELECT image_data,image_content_type FROM emergency_history WHERE emergency_id=?", (rs, n) -> new ImageData(rs.getBytes(1), rs.getString(2)), id, id).stream().filter(i -> i.bytes() != null).findFirst();
    }
    public List<MessageView> findMessages(String id) { return jdbc.query("SELECT message_id,emergency_id,sender_role,sender_name,message_text,created_at FROM emergency_messages WHERE emergency_id=? ORDER BY created_at", (rs,n)->new MessageView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant().toString()), id); }
    public MessageView addMessage(String id, String role, String name, String message) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO emergency_messages(emergency_id,sender_role,sender_name,message_text) VALUES(?,?,?,?)", new String[]{"message_id"});
            ps.setString(1,id); ps.setString(2,role); ps.setString(3,name); ps.setString(4,message); return ps;
        }, key);
        Number generated = key.getKey();
        return jdbc.queryForObject("SELECT message_id,emergency_id,sender_role,sender_name,message_text,created_at FROM emergency_messages WHERE message_id=?", (rs,n)->new MessageView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant().toString()), generated);
    }
    public RescueSummary summary() { return jdbc.queryForObject("SELECT COUNT(*),COALESCE(SUM(people_needing_help),0) FROM emergencies", (rs,n)->new RescueSummary(rs.getInt(1),rs.getInt(2))); }
    public RescueAnalytics analytics() {
        // Averaged in Java so the same query works on H2 and PostgreSQL.
        List<Double> seconds = jdbc.query("SELECT created_at,helper_assigned_at FROM emergencies WHERE helper_assigned_at IS NOT NULL",
                (rs, n) -> (rs.getTimestamp(2).getTime() - rs.getTimestamp(1).getTime()) / 1000.0);
        return new RescueAnalytics(seconds.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    // ---- AI triage -------------------------------------------------------------------------------------------

    /** Stores triage output. Priority may only go up, never down, and the change is recorded in the flags. */
    @Transactional
    public Optional<EmergencyView> saveAi(String id, TriageResult r, String duplicateOf) {
        Optional<EmergencyView> current = findById(id);
        if (current.isEmpty()) return Optional.empty();
        String currentPriority = current.get().priority();
        String newPriority = TriageResult.higher(currentPriority, r.priority());
        boolean raised = TriageResult.rank(newPriority) > TriageResult.rank(currentPriority);
        List<String> flags = new ArrayList<>(r.flags());
        if (raised && !flags.contains("PRIORITY_RAISED")) flags.add("PRIORITY_RAISED");
        if (duplicateOf != null && !flags.contains("POSSIBLE_DUPLICATE")) flags.add("POSSIBLE_DUPLICATE");
        jdbc.update("UPDATE emergencies SET priority=?,critical=CASE WHEN ?='CRITICAL' THEN TRUE ELSE critical END,ai_priority=?,ai_type=?,ai_summary=?,ai_translation=?,ai_flags=?,ai_confidence=?,ai_source=?,ai_duplicate_of=?,ai_updated_at=CURRENT_TIMESTAMP WHERE emergency_id=?",
                newPriority, newPriority, r.priority(), r.type(), r.summary(), r.translation(), String.join(",", flags), r.confidence(), r.source(), duplicateOf, id);
        return findById(id);
    }

    public List<OpenPoint> findOpenPoints() {
        return jdbc.query("SELECT emergency_id,latitude,longitude,phone,created_at FROM emergencies",
                (rs, n) -> new OpenPoint(rs.getString(1), nullableDouble(rs, "latitude"), nullableDouble(rs, "longitude"), rs.getString(4), rs.getTimestamp(5).toInstant()));
    }

    public boolean tokenBelongsToRequest(String id, String token) {
        return token != null && findById(id).map(v -> v.trackingToken() != null && MessageDigest.isEqual(
                v.trackingToken().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))).orElse(false);
    }

    // ---- helper location + nearest helper ----------------------------------------------------------------------

    public void setHelperLocation(String helperId, double latitude, double longitude) {
        jdbc.update("UPDATE helpers SET latitude=?,longitude=?,location_updated_at=CURRENT_TIMESTAMP WHERE helper_id=?", latitude, longitude, helperId);
    }

    public Optional<HelperView> findHelperByName(String name) {
        return jdbc.query(HELPER_SELECT + " WHERE LOWER(name)=LOWER(?)", this::mapHelper, name).stream().findFirst();
    }

    public List<HelperLoad> findHelperLoads() {
        java.util.Map<String, Integer> active = new java.util.HashMap<>();
        jdbc.query("SELECT LOWER(assigned_team),COUNT(*) FROM emergencies WHERE assigned_team IS NOT NULL AND assigned_team<>'' GROUP BY LOWER(assigned_team)",
                rs -> { active.put(rs.getString(1), rs.getInt(2)); });
        return jdbc.query("SELECT helper_id,name,latitude,longitude,location_updated_at FROM helpers ORDER BY name", (rs, n) -> {
            Timestamp at = rs.getTimestamp(5);
            return new HelperLoad(rs.getString(1), rs.getString(2), nullableDouble(rs, "latitude"), nullableDouble(rs, "longitude"),
                    at == null ? null : at.toInstant(), active.getOrDefault(rs.getString(2).toLowerCase(), 0));
        });
    }

    // ---- push tokens ----------------------------------------------------------------------------------------

    @Transactional
    public void saveDeviceToken(String token, String role, String refId) {
        jdbc.update("DELETE FROM device_tokens WHERE token=?", token);
        jdbc.update("INSERT INTO device_tokens(token,role,ref_id) VALUES(?,?,?)", token, role, refId);
    }
    public List<String> findDeviceTokens(String role, String refId) {
        return jdbc.query("SELECT token FROM device_tokens WHERE role=? AND ref_id=?", (rs, n) -> rs.getString(1), role, refId);
    }
    public void deleteDeviceToken(String token) { jdbc.update("DELETE FROM device_tokens WHERE token=?", token); }

    public boolean helperNameTaken(String name) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM helpers WHERE LOWER(name)=LOWER(?))", Boolean.class, name)); }
    public HelperView createHelper(String name, String phone, String pin) {
        String id = "HLP-" + UUID.randomUUID().toString().substring(0,8).toUpperCase();
        jdbc.update("INSERT INTO helpers(helper_id,name,phone,pin_hash) VALUES(?,?,?,?)", id,name,phone,hashPin(pin));
        return findHelper(id).orElseThrow();
    }
    private static final String HELPER_SELECT = "SELECT helper_id,name,phone,created_at,latitude,longitude,location_updated_at FROM helpers";
    public List<HelperView> findHelpers() { return jdbc.query(HELPER_SELECT + " ORDER BY name", this::mapHelper); }
    public Optional<HelperView> findHelper(String id) { return jdbc.query(HELPER_SELECT + " WHERE helper_id=?", this::mapHelper, id).stream().findFirst(); }
    private HelperView mapHelper(ResultSet rs,int row) throws SQLException {
        Timestamp at = rs.getTimestamp(7);
        return new HelperView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant().toString(),
                nullableDouble(rs, "latitude"), nullableDouble(rs, "longitude"), at == null ? null : at.toInstant().toString());
    }
    public Optional<HelperView> authenticateHelper(String id, String pin) {
        Optional<String> hash = jdbc.query("SELECT pin_hash FROM helpers WHERE helper_id=?", (rs, n) -> rs.getString(1), id).stream().findFirst();
        if (hash.isEmpty() || !MessageDigest.isEqual(hash.get().getBytes(StandardCharsets.UTF_8), hashPin(pin).getBytes(StandardCharsets.UTF_8))) return Optional.empty();
        return findHelper(id);
    }
    public void setHelperPin(String id,String pin) { jdbc.update("UPDATE helpers SET pin_hash=? WHERE helper_id=?",hashPin(pin),id); }
    public boolean deleteHelper(String id) { jdbc.update("UPDATE emergencies SET assigned_team=NULL,status='UNDER REVIEW',estimated_arrival=NULL,updated_at=CURRENT_TIMESTAMP WHERE assigned_team=(SELECT name FROM helpers WHERE helper_id=?)",id); return jdbc.update("DELETE FROM helpers WHERE helper_id=?",id)>0; }
    private static String hashPin(String pin) {
        try { byte[] digest=MessageDigest.getInstance("SHA-256").digest(("kerala-rescue-helper-pin:"+pin).getBytes(StandardCharsets.UTF_8)); return Base64.getEncoder().encodeToString(digest); }
        catch(Exception e){ throw new IllegalStateException(e); }
    }
    private static String value(String v,String fallback){return v==null||v.isBlank()?fallback:v.trim();}
    private static String blankToNull(String v){return v==null||v.isBlank()?null:v.trim();}
}

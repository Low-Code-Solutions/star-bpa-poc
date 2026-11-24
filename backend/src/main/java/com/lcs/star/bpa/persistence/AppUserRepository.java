package com.lcs.star.bpa.persistence;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * app_user (data model 5.10; BPA-76), read only here: the portal users, written by the S22 persona sync alone (L2). Camunda is the SOR of the user name, myPeople
 * of the employee reference and the corporate email system of the address (all synthetic in the POC). The technical user {@code bpa-service} is a Camunda user
 * and has no row, so it is never listed.
 */
@Repository
public class AppUserRepository {

    /** One portal user as the table has it; the groups come from Camunda, not from here. */
    public record AppUser(String username, String employeeRef, String email) {
    }

    private final JdbcTemplate jdbc;

    public AppUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every portal user, by username in byte order, so the order is the same whatever the database collation (ASSUMPTION: no source orders them). */
    public List<AppUser> all() {
        return jdbc.query("SELECT username, employee_ref, email FROM bpa.app_user ORDER BY username COLLATE \"C\"",
                (rs, n) -> new AppUser(rs.getString("username"), rs.getString("employee_ref"), rs.getString("email")));
    }

    public boolean exists(String username) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bpa.app_user WHERE username = ?)", Boolean.class, username));
    }
}

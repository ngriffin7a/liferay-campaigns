// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.dto;

import java.util.HashSet;
import java.util.Set;

public class Recipient {
    private String emailAddress;
    private String name;
    private String userId;
    private Set<String> userGroupNames = new HashSet<>();

    public Recipient() {
    }

    public Recipient(String emailAddress, String userId, String name) {
        this.emailAddress = emailAddress;
        this.userId = userId;
        this.name = name;
    }

    public void addUserGroupName(String userGroupName) {
        if (userGroupName != null && !userGroupName.trim().isEmpty()) {
            userGroupNames.add(userGroupName.trim());
        }
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Set<String> getUserGroupNames() {
        return userGroupNames;
    }

    public void setUserGroupNames(Set<String> userGroupNames) {
        this.userGroupNames = userGroupNames;
    }
}

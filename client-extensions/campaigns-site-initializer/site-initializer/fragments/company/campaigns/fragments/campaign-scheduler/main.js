function dateFormatter(value, row) {
	if (value) {
		const date = new Date(value);
		const month = ('0' + (date.getMonth() + 1)).slice(-2);
		const day = ('0' + date.getDate()).slice(-2);
		const year = date.getFullYear();
		const hours = ('0' + date.getHours()).slice(-2);
		const minutes = ('0' + date.getMinutes()).slice(-2);
		return `${month}/${day}/${year} ${hours}:${minutes}`;
	}
	return "";
}

function statusFormatter(value, row) {
    let statusCode;
    let statusLabel;

    if (value && typeof value === 'object') {
        statusCode = value.code;
        statusLabel = value.label_i18n || value.label || String(value.code);
    } else {
        statusCode = value;
        if (value === 0) statusLabel = "Approved";
        else if (value === 1) statusLabel = "Pending";
        else if (value === 2) statusLabel = "Draft";
        else if (value === 3) statusLabel = "Expired";
        else if (value === 4) statusLabel = "Denied";
        else if (value === 5) statusLabel = "Inactive";
        else if (value === 6) statusLabel = "Incomplete";
        else if (value === 8) statusLabel = "Scheduled";
        else statusLabel = String(value);
    }

    let labelClass = "label-secondary";
    if (statusCode === 0) {
        labelClass = "label-success";
    } else if (statusCode === 1) {
        labelClass = "label-info";
    } else if (statusCode === 3 || statusCode === 4) {
        labelClass = "label-warning";
    }

    // This is an html:true column (raw innerHTML), so escape the dynamic label.
    // labelClass is a fixed, code-derived value and needs no escaping.
    const safeLabel = ClayTable.escapeHtml(String(statusLabel));
    return `<span class="label ${labelClass}"><span class="label-item label-item-expand">${safeLabel}</span></span>`;
}

function userGroupFormatter(value, row) {
	if (!value) return "";
	const ids = String(value).split(',').map(id => id.trim()).filter(id => id);
	if (typeof userGroupsLoaded !== 'undefined' && userGroupsLoaded && allUserGroups && allUserGroups.length > 0) {
		const names = ids.map(id => {
			const group = allUserGroups.find(g => String(g.id) === id);
			return group ? group.name : id;
		});
		return names.join(', ');
	}
	return value;
}

function userFormatter(value, row) {
	if (!value) return "";
	const ids = String(value).split(',').map(id => id.trim()).filter(id => id);
	if (typeof usersLoaded !== 'undefined' && usersLoaded && allUsers && allUsers.length > 0) {
		const names = ids.map(id => {
			const user = allUsers.find(u => String(u.id) === id);
			if (user) {
				return user.name || user.emailAddress || id;
			}
			return id;
		});
		return names.join(', ');
	}
	return value;
}

/* modal variables */
const newCampaignModal = fragmentElement.querySelector('#newCampaignModal');
const newCampaignForm = fragmentElement.querySelector('#newCampaignForm');

/* oauth client setup */
// The OAuth2 client is discovered via the ES module imported in index.html
// (@liferay/oauth2-provider-web/client). FromUserAgentApplication is async in
// 2025.Q2+ and the deprecated global Liferay.OAuth2Client is no longer
// available without a feature flag, so the module script resolves the client
// and exposes a Promise-based apiFetch on the fragment element / window for
// this classic script to consume.
function getApiFetch() {
    const panel = fragmentElement.querySelector('.fragment-campaign-scheduler')
        || document.querySelector('.fragment-campaign-scheduler');
    return (panel && panel._apiFetch) || window.__campaignsApiFetch || null;
}

let objectDefinitionsLoaded = false;
let userGroupsLoaded = false;
let loadedObjectDefinitions = [];
let blueprintsLoaded = false;

function loadObjectDefinitions() {
    if (objectDefinitionsLoaded) return Promise.resolve();
    
    const select = fragmentElement.querySelector('#campaignObjectDefinitionId');
    select.innerHTML = '<option value="" selected>Loading...</option>';
    
    // Adding OData filter based on requirement
    return Liferay.Util.fetch(`/o/object-admin/v1.0/object-definitions?pageSize=200&filter=objectFolderExternalReferenceCode%20eq%20'L_CMS_CONTENT_STRUCTURES'`)
        .then(res => res.json())
        .then(data => {
            select.innerHTML = '<option value="" selected>Select a Content Type...</option>';
            if (data.items) {
                loadedObjectDefinitions = data.items;
                // Use English label as display text, falling back to name. Sort by display text.
                data.items.sort((a,b) => {
                    const labelA = (a.label && a.label.en_US) ? a.label.en_US : a.name;
                    const labelB = (b.label && b.label.en_US) ? b.label.en_US : b.name;
                    return labelA.localeCompare(labelB);
                }).forEach(def => {
                    const opt = document.createElement('option');
                    opt.value = def.id;
                    opt.textContent = (def.label && def.label.en_US) ? def.label.en_US : def.name;
                    select.appendChild(opt);
                });
            }
            objectDefinitionsLoaded = true;
        })
        .catch(err => {
            console.error('Failed to load object definitions', err);
            select.innerHTML = '<option value="" selected>Error loading definitions</option>';
        });
}

let allUserGroups = [];
let selectedUserGroupIds = new Set();

function loadUserGroups() {
    if (userGroupsLoaded) return Promise.resolve();
    
    const listContainer = fragmentElement.querySelector('#campaignUserGroupList');
    if (!listContainer) return Promise.resolve();
    listContainer.innerHTML = '<div class="text-muted small p-1">Loading user groups...</div>';
    
    return Liferay.Util.fetch(`/o/headless-admin-user/v1.0/user-groups?pageSize=200`)
        .then(res => res.json())
        .then(data => {
            if (data.items) {
                allUserGroups = data.items.sort((a,b) => (a.name || '').localeCompare(b.name || ''));
                renderUserGroupList(allUserGroups);
            } else {
                listContainer.innerHTML = '<div class="text-muted small p-1">No user groups available</div>';
            }
            userGroupsLoaded = true;
            
            const groupSearchInput = fragmentElement.querySelector('#campaignUserGroupSearch');
            const clearGroupBtn = fragmentElement.querySelector('#clearUserGroupSearchBtn');
            
            if (groupSearchInput) {
                groupSearchInput.addEventListener('input', (e) => {
                    const term = e.target.value.toLowerCase();
                    
                    if (clearGroupBtn) {
                        if (term.length > 0) {
                            clearGroupBtn.classList.remove('d-none');
                        } else {
                            clearGroupBtn.classList.add('d-none');
                        }
                    }
                    
                    const filtered = allUserGroups.filter(g => {
                        const name = (g.name || '').toLowerCase();
                        return name.includes(term);
                    });
                    renderUserGroupList(filtered);
                });
                
                if (clearGroupBtn) {
                    clearGroupBtn.addEventListener('click', () => {
                        groupSearchInput.value = '';
                        clearGroupBtn.classList.add('d-none');
                        renderUserGroupList(allUserGroups);
                    });
                }
            }
        })
        .catch(err => {
            console.error('Failed to load user groups', err);
            listContainer.innerHTML = '<div class="text-danger small p-1">Error loading user groups</div>';
        });
}

function renderUserGroupList(groupsToRender) {
    const listContainer = fragmentElement.querySelector('#campaignUserGroupList');
    if (!listContainer) return;
    listContainer.innerHTML = '';
    
    if (groupsToRender.length === 0) {
        listContainer.innerHTML = '<div class="text-muted small p-1">No user groups found</div>';
        return;
    }
    
    groupsToRender.forEach(group => {
        const div = document.createElement('div');
        div.className = 'custom-control custom-checkbox mb-1 d-flex align-items-baseline';
        
        const input = document.createElement('input');
        input.type = 'checkbox';
        input.className = 'custom-control-input group-checkbox';
        input.id = `group_chk_${group.id}`;
        input.value = group.id;
        if (selectedUserGroupIds.has(String(group.id))) {
            input.checked = true;
        }
        
        input.addEventListener('change', (e) => {
            if (e.target.checked) {
                selectedUserGroupIds.add(String(group.id));
            } else {
                selectedUserGroupIds.delete(String(group.id));
            }
            updateSelectedUserGroupsCount();
            updateHiddenUserGroupIds();
        });
        
        const label = document.createElement('label');
        label.className = 'custom-control-label font-weight-normal d-flex';
        label.style.minWidth = '0';
        label.style.width = '100%';
        label.htmlFor = `group_chk_${group.id}`;
        
        const span = document.createElement('span');
        span.className = 'text-truncate';
        span.title = group.name || '';
        span.textContent = group.name || '';
        
        label.appendChild(span);
        
        div.appendChild(input);
        div.appendChild(label);
        listContainer.appendChild(div);
    });
}

function updateSelectedUserGroupsCount() {
    const countSpan = fragmentElement.querySelector('#selectedUserGroupsCount');
    if (countSpan) {
        countSpan.textContent = selectedUserGroupIds.size;
    }
}

function updateHiddenUserGroupIds() {
    const hiddenInput = fragmentElement.querySelector('#campaignRecipientUserGroupIds');
    if (hiddenInput) {
        hiddenInput.value = Array.from(selectedUserGroupIds).join(',');
    }
}

let usersLoaded = false;
let allUsers = [];
let selectedUserIds = new Set();

function loadUsers() {
    if (usersLoaded) return Promise.resolve();
    
    const listContainer = fragmentElement.querySelector('#campaignUserList');
    if (!listContainer) return Promise.resolve();
    listContainer.innerHTML = '<div class="text-muted small p-1">Loading users...</div>';
    
    return Liferay.Util.fetch(`/o/headless-admin-user/v1.0/user-accounts?pageSize=500`)
        .then(res => res.json())
        .then(data => {
            if (data.items) {
                allUsers = data.items.sort((a,b) => (a.name || '').localeCompare(b.name || ''));
                renderUserList(allUsers);
            } else {
                listContainer.innerHTML = '<div class="text-muted small p-1">No users available</div>';
            }
            usersLoaded = true;
            
            const userSearchInput = fragmentElement.querySelector('#campaignUserSearch');
            const clearUserBtn = fragmentElement.querySelector('#clearUserSearchBtn');
            
            if (userSearchInput) {
                userSearchInput.addEventListener('input', (e) => {
                    const term = e.target.value.toLowerCase();
                    
                    if (clearUserBtn) {
                        if (term.length > 0) {
                            clearUserBtn.classList.remove('d-none');
                        } else {
                            clearUserBtn.classList.add('d-none');
                        }
                    }
                    
                    const filtered = allUsers.filter(u => {
                        const name = (u.name || '').toLowerCase();
                        const email = (u.emailAddress || '').toLowerCase();
                        return name.includes(term) || email.includes(term);
                    });
                    renderUserList(filtered);
                });
                
                if (clearUserBtn) {
                    clearUserBtn.addEventListener('click', () => {
                        userSearchInput.value = '';
                        clearUserBtn.classList.add('d-none');
                        renderUserList(allUsers);
                    });
                }
            }
        })
        .catch(err => {
            console.error('Failed to load users', err);
            listContainer.innerHTML = '<div class="text-danger small p-1">Error loading users</div>';
        });
}

function renderUserList(usersToRender) {
    const listContainer = fragmentElement.querySelector('#campaignUserList');
    if (!listContainer) return;
    listContainer.innerHTML = '';
    
    if (usersToRender.length === 0) {
        listContainer.innerHTML = '<div class="text-muted small p-1">No users found</div>';
        return;
    }
    
    usersToRender.forEach(user => {
        const div = document.createElement('div');
        div.className = 'custom-control custom-checkbox mb-1 d-flex align-items-baseline';
        
        const input = document.createElement('input');
        input.type = 'checkbox';
        input.className = 'custom-control-input user-checkbox';
        input.id = `user_chk_${user.id}`;
        input.value = user.id;
        if (selectedUserIds.has(String(user.id))) {
            input.checked = true;
        }
        
        input.addEventListener('change', (e) => {
            if (e.target.checked) {
                selectedUserIds.add(String(user.id));
            } else {
                selectedUserIds.delete(String(user.id));
            }
            updateSelectedUsersCount();
            updateHiddenUserIds();
        });
        
        const label = document.createElement('label');
        label.className = 'custom-control-label font-weight-normal d-flex';
        label.style.minWidth = '0';
        label.style.width = '100%';
        label.htmlFor = `user_chk_${user.id}`;
        
        let text = user.name || '';
        if (user.emailAddress) {
            text += ` (${user.emailAddress})`;
        }
        
        const span = document.createElement('span');
        span.className = 'text-truncate';
        span.title = text;
        span.textContent = text;
        
        label.appendChild(span);
        
        div.appendChild(input);
        div.appendChild(label);
        listContainer.appendChild(div);
    });
}

function updateSelectedUsersCount() {
    const countSpan = fragmentElement.querySelector('#selectedUsersCount');
    if (countSpan) {
        countSpan.textContent = selectedUserIds.size;
    }
}

function updateHiddenUserIds() {
    const hiddenInput = fragmentElement.querySelector('#campaignRecipientUserIds');
    if (hiddenInput) {
        hiddenInput.value = Array.from(selectedUserIds).join(',');
    }
}

let segmentsLoaded = false;

function loadSegments() {
    if (segmentsLoaded) return Promise.resolve();

    const select = fragmentElement.querySelector('#campaignSegmentId');
    if (!select) return Promise.resolve();
    select.innerHTML = '<option value="" selected>Loading segments...</option>';

    const siteId = Liferay.ThemeDisplay.getScopeGroupId();

    return Liferay.Util.fetch(`/o/headless-admin-user/v1.0/sites/${siteId}/segments?pageSize=200`)
        .then(res => res.json())
        .then(data => {
            select.innerHTML = '<option value="" selected>Select a Segment...</option>';
            if (data.items && data.items.length > 0) {
                data.items
                    .sort((a, b) => (a.name || '').localeCompare(b.name || ''))
                    .forEach(segment => {
                        const opt = document.createElement('option');
                        opt.value = segment.id;
                        opt.textContent = segment.name || `Segment ${segment.id}`;
                        select.appendChild(opt);
                    });
            }
            segmentsLoaded = true;
        })
        .catch(err => {
            console.error('Failed to load segments', err);
            select.innerHTML = '<option value="" selected>Error loading segments</option>';
        });
}

let currentEditCampaignId = null;
let editObjectEntryId = null;
let editObjectEntryTitle = '';

// hydrate() callback for the ClayModalForm controller: fill the form fields
// for edit (or leave them reset for create). The modal's open/close, title, and
// submit-button label are handled by the controller.
function hydrateCampaignForm(campaignToEdit) {
    currentEditCampaignId = null;
    editObjectEntryId = null;
    editObjectEntryTitle = '';

    if (campaignToEdit) {
        currentEditCampaignId = campaignToEdit.id;

        fragmentElement.querySelector('#campaignTitle').value = campaignToEdit.title || '';

        if (campaignToEdit.scheduledSendDate) {
            // Need to format to datetime-local
            const d = new Date(campaignToEdit.scheduledSendDate);
            const isoStr = new Date(d.getTime() - (d.getTimezoneOffset() * 60000)).toISOString();
            fragmentElement.querySelector('#campaignScheduleDateTime').value = isoStr.slice(0, 16);
        }

        fragmentElement.querySelector('#campaignRecipientEmailAddresses').value = campaignToEdit.recipientEmailAddresses || '';

        // Remember the stored content entry so it can be re-selected once the
        // entry list loads -- even if it falls outside the search page.
        editObjectEntryId = campaignToEdit.objectEntryId || null;
        editObjectEntryTitle = campaignToEdit.objectEntryTitle || '';
    }

    const entrySelect = fragmentElement.querySelector('#campaignObjectEntryId');
    if (entrySelect) {
        entrySelect.innerHTML = '<option value="" selected>Select a Content Type first...</option>';
        entrySelect.disabled = true;
    }

    // Editing always resolves to a stored objectDefinitionId, so reset to
    // Content Type mode on open (Blueprint is a selection aid, not persisted).
    const typeRadio = fragmentElement.querySelector('#campaignSelectModeType');
    if (typeRadio) {
        typeRadio.checked = true;
        typeRadio.dispatchEvent(new Event('change'));
    }

    loadObjectDefinitions().then(() => {
        if (campaignToEdit && campaignToEdit.objectDefinitionId) {
            fragmentElement.querySelector('#campaignObjectDefinitionId').value = campaignToEdit.objectDefinitionId;
            // Trigger change to load entries
            const event = new Event('change');
            fragmentElement.querySelector('#campaignObjectDefinitionId').dispatchEvent(event);
        }
    });
    
    loadUserGroups().then(() => {
        selectedUserGroupIds.clear();
        if (campaignToEdit && campaignToEdit.recipientUserGroupIds) {
            const ids = campaignToEdit.recipientUserGroupIds.split(',').map(id => id.trim()).filter(id => id);
            ids.forEach(id => selectedUserGroupIds.add(String(id)));
        }
        updateSelectedUserGroupsCount();
        updateHiddenUserGroupIds();
        renderUserGroupList(allUserGroups);
        
        const groupSearchInput = fragmentElement.querySelector('#campaignUserGroupSearch');
        const clearGroupBtn = fragmentElement.querySelector('#clearUserGroupSearchBtn');
        if (groupSearchInput) {
            groupSearchInput.value = '';
        }
        if (clearGroupBtn) {
            clearGroupBtn.classList.add('d-none');
        }
    });

    loadUsers().then(() => {
        selectedUserIds.clear();
        if (campaignToEdit && campaignToEdit.recipientUserIds) {
            const ids = campaignToEdit.recipientUserIds.split(',').map(id => id.trim()).filter(id => id);
            ids.forEach(id => selectedUserIds.add(String(id)));
        }
        updateSelectedUsersCount();
        updateHiddenUserIds();
        renderUserList(allUsers);
        
        const userSearchInput = fragmentElement.querySelector('#campaignUserSearch');
        const clearUserBtn = fragmentElement.querySelector('#clearUserSearchBtn');
        if (userSearchInput) {
            userSearchInput.value = '';
        }
        if (clearUserBtn) {
            clearUserBtn.classList.add('d-none');
        }
    });

    loadSegments();
}

// Recover the object definition's REST context path (e.g. "/o/c/newsarticles")
// from a search result's action href so we can map an entry back to its object
// definition. Used in blueprint mode, where the content type isn't picked
// directly.
function deriveRestContextPath(embedded) {
    const actions = embedded && embedded.actions;
    const href = actions && ((actions.get && actions.get.href) || (actions.update && actions.update.href));
    if (!href) return null;
    return href.replace(/^https?:\/\/[^/]+/, '').replace(/\/\d+$/, '');
}

function defByRestContextPath(path) {
    return loadedObjectDefinitions.find(d => d.restContextPath === path);
}

// Populate the shared Content Title (#campaignObjectEntryId) dropdown from
// search items. In Content Type mode the definition is known (knownDef); in
// blueprint mode it's derived per entry from the entry's REST href so the
// selected option carries the objectDefinitionId needed at save time. Returns
// true if any option was added.
function populateEntryOptions(entrySelect, items, knownDef) {
    entrySelect.innerHTML = '<option value="" selected>Select an entry...</option>';
    let optionsAdded = false;
    items.forEach(result => {
        const embedded = result.embedded;
        if (!embedded || !embedded.id) return;

        let def = knownDef;
        if (!def) {
            const path = deriveRestContextPath(embedded);
            def = path ? defByRestContextPath(path) : null;
        }

        // The human-readable title lives in the nested object entry under the
        // definition's title field; the search document's top-level `title` can
        // be a bare entry ID.
        const titleField = (def && def.titleObjectFieldName) || 'title';

        const opt = document.createElement('option');
        opt.value = embedded.id;
        opt.textContent = embedded[titleField] || `Entry ${embedded.id}`;
        if (def) {
            opt.dataset.objectDefinitionId = def.id;
            opt.dataset.objectDefinitionTitle = (def.label && def.label.en_US) ? def.label.en_US : def.name;
        }
        entrySelect.appendChild(opt);
        optionsAdded = true;
    });
    return optionsAdded;
}

// Load collection-style Search Blueprints (those bound to a content type, so
// they list object entries). Their sortConfiguration can order by
// displayDate_sortable — something the search REST `sort` param can't do.
function loadBlueprints() {
    if (blueprintsLoaded) return Promise.resolve();

    const select = fragmentElement.querySelector('#campaignBlueprintErc');
    select.innerHTML = '<option value="" selected>Loading...</option>';

    return Liferay.Util.fetch('/o/search-experiences-rest/v1.0/sxp-blueprints?pageSize=200')
        .then(res => res.json())
        .then(data => {
            select.innerHTML = '<option value="" selected>Select a Blueprint...</option>';
            const items = (data.items || []).filter(b => b.collectionProviderTypeName);
            items.map(b => {
                const titleObj = b.title;
                const title = (titleObj && typeof titleObj === 'object')
                    ? (titleObj.en_US || Object.values(titleObj)[0] || b.externalReferenceCode)
                    : (titleObj || b.externalReferenceCode);
                return { erc: b.externalReferenceCode, title: title };
            }).sort((a, b) => a.title.localeCompare(b.title)).forEach(b => {
                const opt = document.createElement('option');
                opt.value = b.erc;
                opt.textContent = b.title;
                select.appendChild(opt);
            });
            blueprintsLoaded = true;
        })
        .catch(err => {
            console.error('Failed to load blueprints', err);
            select.innerHTML = '<option value="" selected>Error loading blueprints</option>';
        });
}

// Content Type mode: list entries for the selected object definition, narrowed
// to one Space (scope = group id; empty means All Spaces) and ordered by the
// chosen sort (a search REST `field:direction` value such as "title:asc").
// title, dateCreated and dateModified are the index's sortable properties.
function loadEntriesForType(def, scope, sort) {
    const entrySelect = fragmentElement.querySelector('#campaignObjectEntryId');
    entrySelect.innerHTML = '<option value="" selected>Loading...</option>';
    entrySelect.disabled = true;

    let searchUrl = `/o/search/v1.0/search?emptySearch=true&nestedFields=embedded&filter=objectDefinitionId%20eq%20${def.id}&page=1&pageSize=200`;
    if (scope) {
        searchUrl += `&scope=${encodeURIComponent(scope)}`;
    }
    if (sort) {
        searchUrl += `&sort=${encodeURIComponent(sort)}`;
    }

    return Liferay.Util.fetch(searchUrl)
        .then(res => res.json())
        .then(data => {
            if (data.items && data.items.length > 0 && populateEntryOptions(entrySelect, data.items, def)) {
                entrySelect.disabled = false;
                // Pre-select the stored entry when editing. The entry may not be
                // in this search page (sort/200-cap), so inject an option for it
                // if absent before selecting.
                if (currentEditCampaignId !== null && editObjectEntryId) {
                    const target = String(editObjectEntryId);
                    if (!Array.from(entrySelect.options).some(o => o.value === target)) {
                        const opt = document.createElement('option');
                        opt.value = target;
                        opt.textContent = editObjectEntryTitle || `Entry ${target}`;
                        entrySelect.appendChild(opt);
                    }
                    entrySelect.value = target;
                }
            } else if (currentEditCampaignId !== null && editObjectEntryId) {
                // No search results at all, but we still know the stored entry.
                const target = String(editObjectEntryId);
                entrySelect.innerHTML = '';
                const opt = document.createElement('option');
                opt.value = target;
                opt.textContent = editObjectEntryTitle || `Entry ${target}`;
                entrySelect.appendChild(opt);
                entrySelect.value = target;
                entrySelect.disabled = false;
            } else {
                entrySelect.innerHTML = '<option value="" selected>No entries found</option>';
            }
        })
        .catch(err => {
            console.error('Failed to load object entries', err);
            entrySelect.innerHTML = '<option value="" selected>Error loading entries</option>';
        });
}

// Populate the Space dropdown with the distinct scopes that actually contain
// entries of the selected content type. There's no spaces/asset-library listing
// endpoint and the search API has no scope facet, so we page through the
// definition's entries (requesting only scope fields to keep payloads small)
// collecting distinct scopeId/scopeKey pairs.
const SPACE_DISCOVERY_PAGE_SIZE = 500;
const SPACE_DISCOVERY_MAX_PAGES = 20;
function discoverSpaces(defId) {
    const spaceSelect = fragmentElement.querySelector('#campaignSpaceScope');
    spaceSelect.disabled = true;

    const seen = new Map();
    function fetchPage(page) {
        const url = `/o/search/v1.0/search?emptySearch=true&nestedFields=embedded&fields=embedded.scopeId,embedded.scopeKey&filter=objectDefinitionId%20eq%20${defId}&page=${page}&pageSize=${SPACE_DISCOVERY_PAGE_SIZE}`;
        return Liferay.Util.fetch(url)
            .then(res => res.json())
            .then(data => {
                (data.items || []).forEach(it => {
                    const e = it.embedded;
                    if (e && e.scopeId != null && !seen.has(e.scopeId)) {
                        seen.set(e.scopeId, e.scopeKey || `Scope ${e.scopeId}`);
                    }
                });
                const lastPage = data.lastPage || page;
                if (page < lastPage && page < SPACE_DISCOVERY_MAX_PAGES) {
                    return fetchPage(page + 1);
                }
                if (page >= SPACE_DISCOVERY_MAX_PAGES && page < lastPage) {
                    console.warn(`Space discovery stopped at ${SPACE_DISCOVERY_MAX_PAGES} pages; some spaces may be missing.`);
                }
            });
    }

    return fetchPage(1)
        .then(() => {
            spaceSelect.innerHTML = '<option value="" selected>All Spaces</option>';
            Array.from(seen.entries())
                .sort((a, b) => String(a[1]).localeCompare(String(b[1])))
                .forEach(([scopeId, scopeKey]) => {
                    const opt = document.createElement('option');
                    opt.value = scopeId;
                    opt.textContent = scopeKey;
                    spaceSelect.appendChild(opt);
                });
            spaceSelect.disabled = false;
        })
        .catch(err => {
            console.error('Failed to discover spaces', err);
            // Leave the dropdown at "All Spaces" only — filtering still works.
            spaceSelect.innerHTML = '<option value="" selected>All Spaces</option>';
            spaceSelect.disabled = false;
        });
}

// Read the current Space + Sort selections and (re)load entries for a definition.
function reloadTypeEntries(def) {
    const scope = fragmentElement.querySelector('#campaignSpaceScope').value;
    const sort = fragmentElement.querySelector('#campaignSortBy').value;
    return loadEntriesForType(def, scope, sort);
}

// Content Type mode: selecting a content type enables the Space + Sort
// dropdowns, repopulates the spaces, and loads entries.
fragmentElement.querySelector('#campaignObjectDefinitionId').addEventListener('change', function(e) {
    const defId = parseInt(e.target.value, 10);
    const def = loadedObjectDefinitions.find(d => d.id === defId);
    const entrySelect = fragmentElement.querySelector('#campaignObjectEntryId');
    const sortSelect = fragmentElement.querySelector('#campaignSortBy');
    const spaceSelect = fragmentElement.querySelector('#campaignSpaceScope');

    spaceSelect.innerHTML = '<option value="" selected>All Spaces</option>';
    spaceSelect.disabled = true;

    if (!def || !def.restContextPath) {
        sortSelect.disabled = true;
        entrySelect.innerHTML = '<option value="" selected>Select a Content Type first...</option>';
        entrySelect.disabled = true;
        return;
    }

    sortSelect.disabled = false;
    discoverSpaces(defId);
    reloadTypeEntries(def);
});

// Space change: re-filter the current content type's entries by the chosen scope.
fragmentElement.querySelector('#campaignSpaceScope').addEventListener('change', function() {
    const defId = parseInt(fragmentElement.querySelector('#campaignObjectDefinitionId').value, 10);
    const def = loadedObjectDefinitions.find(d => d.id === defId);
    if (def) {
        reloadTypeEntries(def);
    }
});

// Sort change: reload the current content type's entries in the chosen order.
fragmentElement.querySelector('#campaignSortBy').addEventListener('change', function() {
    const defId = parseInt(fragmentElement.querySelector('#campaignObjectDefinitionId').value, 10);
    const def = loadedObjectDefinitions.find(d => d.id === defId);
    if (def) {
        reloadTypeEntries(def);
    }
});

// Blueprint mode: list entries via the selected Search Blueprint, which applies
// its own sort (e.g. displayDate_sortable: desc) and scope.
fragmentElement.querySelector('#campaignBlueprintErc').addEventListener('change', function(e) {
    const erc = e.target.value;
    const entrySelect = fragmentElement.querySelector('#campaignObjectEntryId');

    if (!erc) {
        entrySelect.innerHTML = '<option value="" selected>Select a Blueprint first...</option>';
        entrySelect.disabled = true;
        return;
    }

    entrySelect.innerHTML = '<option value="" selected>Loading...</option>';
    entrySelect.disabled = true;

    const searchUrl = `/o/search/v1.0/search?emptySearch=true&nestedFields=embedded&blueprintExternalReferenceCode=${encodeURIComponent(erc)}&page=1&pageSize=200`;

    Liferay.Util.fetch(searchUrl)
        .then(res => res.json())
        .then(data => {
            if (data.items && data.items.length > 0 && populateEntryOptions(entrySelect, data.items, null)) {
                entrySelect.disabled = false;
            } else {
                entrySelect.innerHTML = '<option value="" selected>No entries found</option>';
            }
        })
        .catch(err => {
            console.error('Failed to load blueprint entries', err);
            entrySelect.innerHTML = '<option value="" selected>Error loading entries</option>';
        });
});

// Mutually exclusive selection: either pick a Content Type or pick a Blueprint.
// Switching modes hides the other control, clears its value, and resets the
// shared Content Title dropdown.
fragmentElement.querySelectorAll('input[name="campaignSelectMode"]').forEach(radio => {
    radio.addEventListener('change', function() {
        const typeGroup = fragmentElement.querySelector('#campaignContentTypeGroup');
        const spaceGroup = fragmentElement.querySelector('#campaignSpaceGroup');
        const sortGroup = fragmentElement.querySelector('#campaignSortGroup');
        const blueprintGroup = fragmentElement.querySelector('#campaignBlueprintGroup');
        const defSelect = fragmentElement.querySelector('#campaignObjectDefinitionId');
        const blueprintSelect = fragmentElement.querySelector('#campaignBlueprintErc');
        const sortSelect = fragmentElement.querySelector('#campaignSortBy');
        const spaceSelect = fragmentElement.querySelector('#campaignSpaceScope');
        const entrySelect = fragmentElement.querySelector('#campaignObjectEntryId');

        if (this.value === 'blueprint') {
            typeGroup.classList.add('d-none');
            spaceGroup.classList.add('d-none');
            sortGroup.classList.add('d-none');
            blueprintGroup.classList.remove('d-none');
            defSelect.value = '';
            sortSelect.disabled = true;
            spaceSelect.innerHTML = '<option value="" selected>All Spaces</option>';
            spaceSelect.disabled = true;
            entrySelect.innerHTML = '<option value="" selected>Select a Blueprint first...</option>';
            entrySelect.disabled = true;
            loadBlueprints();
        } else {
            blueprintGroup.classList.add('d-none');
            typeGroup.classList.remove('d-none');
            spaceGroup.classList.remove('d-none');
            sortGroup.classList.remove('d-none');
            blueprintSelect.value = '';
            sortSelect.disabled = true;
            spaceSelect.innerHTML = '<option value="" selected>All Spaces</option>';
            spaceSelect.disabled = true;
            entrySelect.innerHTML = '<option value="" selected>Select a Content Type first...</option>';
            entrySelect.disabled = true;
        }
    });
});

// serialize() callback for the ClayModalForm controller: read the form into a
// REST payload, or return null to abort (used here for email validation). The
// submit lifecycle (button state, POST/PUT, toast, table reload) is owned by
// the controller.
function serializeCampaign() {
    const title = fragmentElement.querySelector('#campaignTitle').value;
    const scheduleDateTime = fragmentElement.querySelector('#campaignScheduleDateTime').value;

    const recipientUserGroupIds = fragmentElement.querySelector('#campaignRecipientUserGroupIds').value;
    const recipientUserIds = fragmentElement.querySelector('#campaignRecipientUserIds').value || "";
    const recipientEmailAddresses = fragmentElement.querySelector('#campaignRecipientEmailAddresses').value || "";

    // Validate the additional email addresses (comma-delimited).
    const invalid = ClayModalForm.invalidEmails(recipientEmailAddresses);
    if (invalid.length > 0) {
        openToast(`Invalid email address${invalid.length > 1 ? 'es' : ''}: ${invalid.join(', ')}`, 'danger');
        return null;
    }

    const objectEntryId = fragmentElement.querySelector('#campaignObjectEntryId').value;

    let isoDateTime = '';
    if (scheduleDateTime) {
        isoDateTime = new Date(scheduleDateTime).toISOString();
    }

    const payload = {
        title: title,
        scheduledSendDate: isoDateTime,
        recipientUserGroupIds: recipientUserGroupIds,
        recipientUserIds: recipientUserIds,
        recipientEmailAddresses: recipientEmailAddresses
    };

    // Resolve the content type based on the active selection mode. In Content
    // Type mode it comes from the dropdown; in Blueprint mode it's carried on
    // the selected Content Title option (set by populateEntryOptions).
    const selectionModeRadio = fragmentElement.querySelector('input[name="campaignSelectMode"]:checked');
    const selectionMode = selectionModeRadio ? selectionModeRadio.value : 'type';

    let objectDefinitionId = '';
    let objDefTitle = '';
    if (selectionMode === 'blueprint') {
        const entrySel = fragmentElement.querySelector('#campaignObjectEntryId');
        const selectedOpt = (entrySel && entrySel.selectedIndex >= 0) ? entrySel.options[entrySel.selectedIndex] : null;
        if (selectedOpt && selectedOpt.dataset.objectDefinitionId) {
            objectDefinitionId = selectedOpt.dataset.objectDefinitionId;
            objDefTitle = selectedOpt.dataset.objectDefinitionTitle || '';
        }
    } else {
        const defSelect = fragmentElement.querySelector('#campaignObjectDefinitionId');
        objectDefinitionId = defSelect ? defSelect.value : '';
        if (defSelect && defSelect.options[defSelect.selectedIndex]) {
            objDefTitle = defSelect.options[defSelect.selectedIndex].text;
        }
    }

    if (objectDefinitionId) {
        payload.objectDefinitionId = parseInt(objectDefinitionId, 10);
        payload.objectDefinitionTitle = objDefTitle;
    } else {
        payload.objectDefinitionId = 0;
        payload.objectDefinitionTitle = "";
    }

    if (objectEntryId) {
        let objEntryTitle = "";
        const entrySelect = fragmentElement.querySelector('#campaignObjectEntryId');
        if (entrySelect && entrySelect.options[entrySelect.selectedIndex]) {
            objEntryTitle = entrySelect.options[entrySelect.selectedIndex].text;
        }

        payload.objectEntryId = parseInt(objectEntryId, 10);
        payload.objectEntryTitle = objEntryTitle;
    } else {
        payload.objectEntryId = 0;
        payload.objectEntryTitle = "";
    }

    return payload;
}

function objectDefinitionFormatter(value, row) {
    if (row.objectDefinitionTitle) return row.objectDefinitionTitle;
    return value ? `Definition ${value}` : "";
}

function objectEntryFormatter(value, row) {
    if (row.objectEntryTitle) return row.objectEntryTitle;
    return value ? `Entry ${value}` : "";
}

/* ---------------------------------------------------------------------------
 * Table + modal configuration. The generic engine lives in the Clay Table and
 * Clay Modal Form global libraries (window.ClayTable / window.ClayModalForm);
 * everything below is campaign-specific config supplied to them.
 * ------------------------------------------------------------------------- */

// Column model -- single source of truth for both the header and the rows.
// `html: true` columns insert their rendered output as markup (controlled
// label HTML); everything else is inserted as escaped text.
const CAMPAIGN_COLUMNS = [
    { field: 'title', title: 'Title', sortable: true,
        render: (v, r) => (v == null ? '' : String(v)) },
    { field: 'scheduledSendDate', title: 'Schedule Date Time', sortable: true,
        render: (v, r) => dateFormatter(v, r) },
    { field: 'recipientUserGroupIds', title: 'Recipient User Groups',
        render: (v, r) => userGroupFormatter(v, r) },
    { field: 'objectDefinitionId', title: 'Content Type',
        render: (v, r) => objectDefinitionFormatter(v, r) },
    { field: 'objectEntryId', title: 'Content Title',
        render: (v, r) => objectEntryFormatter(v, r) },
    { field: 'lastSentDate', title: 'Last Sent', sortable: true,
        render: (v, r) => (v ? dateFormatter(v, r) : '—') },
    { field: 'status', title: 'Status', sortable: true, html: true,
        render: (v, r) => statusFormatter(v, r) }
];

function getPageSize() {
    return parseInt(configuration.pageSize) || 25;
}

function openToast(message, type) {
    if (typeof Liferay !== 'undefined' && Liferay.Util && Liferay.Util.openToast) {
        Liferay.Util.openToast({ message: message, type: type });
    } else if (type === 'danger') {
        alert(message);
    }
}

// Ensure the user-group / user lookups are loaded before rendering rows so the
// name formatters can resolve ids to display names.
function ensureLookupsLoaded() {
    return Promise.all([
        userGroupsLoaded ? Promise.resolve() : loadUserGroups().catch(() => null),
        usersLoaded ? Promise.resolve() : loadUsers().catch(() => null)
    ]);
}

// Build the Liferay Objects query string from the table's fetch params
// ({page, pageSize, sortField, sortDir, search}).
function buildCampaignUrl(params) {
    const queryParams = [];

    if (params.search) {
        queryParams.push('search=' + encodeURIComponent(params.search));
    }
    if (params.sortField) {
        queryParams.push('sort=' + encodeURIComponent(params.sortField + ':' + params.sortDir));
    }
    queryParams.push('page=' + params.page);
    queryParams.push('pageSize=' + params.pageSize);

    return '/o/c/campaigns?' + queryParams.join('&');
}

// Send Now is offered only for approved campaigns (status code 0 / "approved").
function isCampaignApproved(row) {
    if (row.status && typeof row.status === 'object') {
        const code = row.status.code;
        const label = row.status.label ? String(row.status.label).toLowerCase() : '';
        return code === 0 || label === 'approved';
    }
    return row.status === 0 || (typeof row.status === 'string' && row.status.toLowerCase() === 'approved');
}

function handleDeleteCampaign(rowId) {
    if (!confirm('Are you sure you want to delete this campaign?')) return;

    Liferay.Util.fetch(`/o/c/campaigns/${rowId}`, { method: 'DELETE' })
        .then(res => {
            if (!res.ok) throw new Error('Failed to delete campaign');
            reloadCampaignTable(false);
            openToast('Campaign deleted successfully.', 'success');
        })
        .catch(err => {
            console.error('Campaign deletion failed:', err);
            openToast('Failed to delete campaign. Please try again.', 'danger');
        });
}

function handleSendNowCampaign(row) {
    if (!row) return;

    const apiFetch = getApiFetch();
    if (!apiFetch) {
        openToast('OAuth2 Client is not available or User Agent ERC is missing.', 'danger');
        return;
    }

    if (!confirm(`Are you sure you want to send the "${row.title}" campaign now?`)) return;

    // Send by id only. The microservice re-fetches the campaign (recipients,
    // content, and approval status) from Liferay using the caller's token, so
    // nothing about the campaign is taken from the browser.
    const url = `${configuration.protocol}://${configuration.hostname}:${configuration.port}/send-email/${row.id}`;

    apiFetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" }
    })
        .then(res => {
            if (!res.ok) throw new Error('Failed to send campaign email');
            openToast('Campaign sent successfully.', 'success');
            // Refresh so the stamped Last Sent date shows and the row's
            // actions switch from "Send Now" to "Clear Last Sent".
            reloadCampaignTable(false);
        })
        .catch(err => {
            console.error('Failed to send campaign email:', err);
            openToast('Failed to send campaign. Please try again.', 'danger');
        });
}

// Clears lastSentDate so an administrator can deliberately re-send a campaign.
// Runs as the logged-in user against the Objects REST API.
//
// Liferay Objects PATCH ignores null/empty values (merge semantics), so it
// CANNOT clear a field -- a null lastSentDate would be silently dropped. A PUT
// (full replace) does clear it: any writable field omitted from the body is
// reset to null. We therefore PUT back every writable field carried on the row
// EXCEPT lastSentDate (and excluding the HATEOAS `actions` node and read-only
// system fields, which PUT rejects). The microservice's send-once guard then
// sees no lastSentDate and allows the send again.
function handleClearLastSent(row) {
    if (!row || !row.id) return;
    if (!confirm('Clear the Last Sent date so this campaign can be sent again?')) return;

    // Writable fields to carry through the replace. lastSentDate is deliberately
    // omitted so the PUT clears it. displayDate/expirationDate/reviewDate are
    // preserved when present so the replace doesn't blank them.
    const WRITABLE = [
        'title', 'scheduledSendDate', 'recipientUserGroupIds', 'recipientUserIds',
        'recipientEmailAddresses', 'objectDefinitionId', 'objectDefinitionTitle',
        'objectEntryId', 'objectEntryTitle', 'externalReferenceCode',
        'displayDate', 'expirationDate', 'reviewDate'
    ];

    const body = {};
    WRITABLE.forEach(function (k) {
        if (row[k] !== undefined && row[k] !== null) body[k] = row[k];
    });

    Liferay.Util.fetch(`/o/c/campaigns/${row.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    })
        .then(res => {
            if (!res.ok) throw new Error('Failed to clear Last Sent date');
            reloadCampaignTable(false);
            openToast('Last Sent date cleared. The campaign can be sent again.', 'success');
        })
        .catch(err => {
            console.error('Failed to clear Last Sent date:', err);
            openToast('Failed to clear Last Sent date. Please try again.', 'danger');
        });
}

/* ---------------------------------------------------------------------------
 * Wire up the Clay Table + Clay Modal Form libraries with campaign config.
 * The libraries load as global JS client extensions; depending on their
 * scriptLocation they may execute after this fragment script, so wait for them
 * to be available rather than assuming they're already present.
 * ------------------------------------------------------------------------- */

// Assigned once the libraries are ready (see whenClayReady); declared at this
// scope so reloadCampaignTable and the row-action handlers can reference them.
let campaignForm;
let campaignTable;

// Reload the table. resetPage=true returns to page 1 (after a search);
// otherwise the current page is reloaded (after a create/update/delete).
function reloadCampaignTable(resetPage) {
    if (campaignTable) campaignTable.reload(resetPage);
}

// Run callback once window.ClayTable / window.ClayModalForm exist. Polls
// briefly so the fragment is resilient to global-JS load order (head or bottom).
function whenClayReady(callback) {
    if (window.ClayTable && window.ClayModalForm) {
        callback();
        return;
    }
    let attempts = 0;
    const timer = setInterval(function () {
        if (window.ClayTable && window.ClayModalForm) {
            clearInterval(timer);
            callback();
        } else if (++attempts > 100) { // ~5s
            clearInterval(timer);
            console.error('Campaign Scheduler: the clayutil-global-js client extension did not load (window.ClayTable / window.ClayModalForm).');
        }
    }, 50);
}

whenClayReady(function () {
    // Create/Update modal controller. The form fields, prefill (hydrate) and
    // payload assembly (serialize) are campaign-specific; open/close, mode,
    // submit, toast and validation plumbing live in the library.
    campaignForm = ClayModalForm.register({
        modal: newCampaignModal,
        form: newCampaignForm,
        createTitle: 'New Campaign',
        editTitle: rec => 'Edit Campaign (ID: ' + rec.id + ')',
        createSubmitLabel: 'Create Campaign',
        editSubmitLabel: 'Update Campaign',
        endpoint: rec => rec
            ? { method: 'PUT', url: '/o/c/campaigns/' + rec.id }
            : { method: 'POST', url: '/o/c/campaigns' },
        hydrate: hydrateCampaignForm,
        serialize: serializeCampaign,
        successMessage: isEdit => isEdit ? 'Campaign updated successfully.' : 'Campaign created successfully.',
        errorMessage: 'Failed to save campaign. Please try again.',
        onSuccess: () => reloadCampaignTable(false)
    });

    // Data table. The library owns rendering, remote sort, pagination and the
    // row action menu; this fragment supplies the columns, the data source, the
    // HATEOAS-driven "New" toggle, and the row actions.
    campaignTable = ClayTable.create({
        root: fragmentElement,
        tableHead: '#campaignTableHead',
        tableBody: '#campaignTableBody',
        paginationBar: '#campaignPaginationBar',
        pageSize: getPageSize(),
        placeholder: 'No campaigns found',
        columns: CAMPAIGN_COLUMNS,
        fetchPage: params => ensureLookupsLoaded()
            .then(() => Liferay.Util.fetch(buildCampaignUrl(params)))
            .then(res => res.json()),
        onData: json => {
            // Show/hide "New" based on HATEOAS actions from the response.
            const newBtn = fragmentElement.querySelector('.btn-new-campaign');
            if (newBtn) {
                newBtn.classList.toggle('d-none', !(json.actions && json.actions.create));
            }
        },
        rowActions: row => {
            const actions = [];
            const alreadySent = !!row.lastSentDate;
            // "Send Now" only for an approved campaign that hasn't been sent yet;
            // once sent, the only way back is to clear Last Sent (matches the
            // microservice's send-once guard).
            if (isCampaignApproved(row) && !alreadySent) {
                actions.push({ label: 'Send Now', icon: 'envelope-closed', onClick: handleSendNowCampaign });
            }
            if (alreadySent) {
                actions.push({ label: 'Clear Last Sent', icon: 'reload', onClick: rec => handleClearLastSent(rec) });
            }
            actions.push({ label: 'Edit', icon: 'pencil', onClick: rec => campaignForm.open(rec) });
            actions.push({ label: 'Delete', icon: 'trash', onClick: rec => handleDeleteCampaign(rec.id) });
            return actions;
        }
    });

    // "New Campaign" button opens the modal in create mode.
    const newCampaignBtn = fragmentElement.querySelector('.btn-new-campaign');
    if (newCampaignBtn) {
        newCampaignBtn.addEventListener('click', e => {
            e.preventDefault();
            campaignForm.open();
        });
    }

    // Search box.
    let searchTimeout;
    const searchInput = fragmentElement.querySelector('#campaign-search-input');
    const searchForm = fragmentElement.querySelector('#campaign-search-form');
    if (searchInput && searchForm) {
        searchInput.addEventListener('keyup', function (e) {
            clearTimeout(searchTimeout);
            const term = e.target.value;
            searchTimeout = setTimeout(() => campaignTable.setSearch(term), 500);
        });

        searchForm.addEventListener('submit', function (e) {
            e.preventDefault();
            campaignTable.setSearch(searchInput.value);
        });
    }

    // Initial load.
    campaignTable.reload();
});

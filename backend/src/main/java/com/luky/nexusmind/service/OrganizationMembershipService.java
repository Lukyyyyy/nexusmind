package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.OrganizationMembership;
import com.luky.nexusmind.model.OrganizationTag;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.OrganizationMembershipRepository;
import com.luky.nexusmind.repository.OrganizationTagRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class OrganizationMembershipService {
    private final OrganizationMembershipRepository repository;
    private final OrganizationTagRepository organizationRepository;
    private final UserRepository userRepository;
    private final OrgTagCacheService cacheService;

    public OrganizationMembershipService(OrganizationMembershipRepository repository,
                                         OrganizationTagRepository organizationRepository,
                                         UserRepository userRepository, OrgTagCacheService cacheService) {
        this.repository = repository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.cacheService = cacheService;
    }

    public List<OrganizationMembership> direct(User user) {
        List<OrganizationMembership> values = repository.findByUserId(user.getId());
        if (values.isEmpty() && user.getOrgTags() != null && !user.getOrgTags().isBlank()) {
            migrateLegacy(user);
            values = repository.findByUserId(user.getId());
        }
        return values;
    }

    public Set<String> effectiveTagIds(User user) {
        Set<String> result = new LinkedHashSet<>();
        for (OrganizationMembership membership : direct(user)) {
            String tagId = membership.getOrganization().getTagId();
            result.add(tagId);
            collectParents(tagId, result);
        }
        result.add("default");
        result.add("DEFAULT");
        return result;
    }

    @Transactional
    public OrganizationMembership add(User user, OrganizationTag organization, OrganizationMembership.Source source) {
        return repository.findByUserIdAndOrganizationTagId(user.getId(), organization.getTagId()).orElseGet(() -> {
            OrganizationMembership value = new OrganizationMembership();
            value.setUser(user);
            value.setOrganization(organization);
            value.setSource(source);
            OrganizationMembership saved = repository.save(value);
            syncLegacy(user);
            invalidate(user);
            return saved;
        });
    }

    @Transactional
    public void remove(User user, String tagId) {
        repository.findByUserIdAndOrganizationTagId(user.getId(), tagId).ifPresent(repository::delete);
        if (tagId.equals(user.getPrimaryOrg())) {
            user.setPrimaryOrg("PRIVATE_" + user.getUsername());
            userRepository.save(user);
        }
        syncLegacy(user);
        invalidate(user);
    }

    public boolean directMember(User user, String tagId) {
        return repository.existsByUserIdAndOrganizationTagId(user.getId(), tagId)
                || direct(user).stream().anyMatch(value -> value.getOrganization().getTagId().equals(tagId));
    }

    private void collectParents(String tagId, Set<String> values) {
        OrganizationTag current = organizationRepository.findByTagId(tagId).orElse(null);
        Set<String> visited = new HashSet<>();
        while (current != null && current.getParentTag() != null && visited.add(current.getTagId())) {
            values.add(current.getParentTag());
            current = organizationRepository.findByTagId(current.getParentTag()).orElse(null);
        }
    }

    @Transactional
    public void migrateLegacy(User user) {
        if (user.getOrgTags() == null) return;
        for (String raw : user.getOrgTags().split(",")) {
            String id = raw.trim();
            if (id.equals("DEFAULT") || id.equals("默认组织")) id = "default";
            organizationRepository.findByTagId(id).ifPresent(tag -> {
                if (!repository.existsByUserIdAndOrganizationTagId(user.getId(), tag.getTagId())) {
                    OrganizationMembership value = new OrganizationMembership();
                    value.setUser(user);
                    value.setOrganization(tag);
                    value.setSource(OrganizationMembership.Source.SYSTEM);
                    repository.save(value);
                }
            });
        }
    }

    private void syncLegacy(User user) {
        user.setOrgTags(String.join(",", repository.findByUserId(user.getId()).stream()
                .map(value -> value.getOrganization().getTagId()).sorted().toList()));
        userRepository.save(user);
    }

    private void invalidate(User user) {
        cacheService.deleteUserOrgTagsCache(user.getUsername());
        cacheService.deleteUserEffectiveTagsCache(user.getUsername());
    }
}

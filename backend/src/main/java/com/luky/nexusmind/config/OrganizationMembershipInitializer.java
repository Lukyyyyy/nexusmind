package com.luky.nexusmind.config;

import com.luky.nexusmind.model.OrganizationMembership;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.OrganizationTagRepository;
import com.luky.nexusmind.repository.UserRepository;
import com.luky.nexusmind.service.OrganizationMembershipService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
public class OrganizationMembershipInitializer implements CommandLineRunner {
    private final UserRepository userRepository;
    private final OrganizationTagRepository organizationRepository;
    private final OrganizationMembershipService membershipService;

    public OrganizationMembershipInitializer(UserRepository userRepository, OrganizationTagRepository organizationRepository,
                                             OrganizationMembershipService membershipService) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.membershipService = membershipService;
    }

    @Override
    public void run(String... args) {
        for (User user : userRepository.findAll()) {
            membershipService.migrateLegacy(user);
            if (user.getRole().isAdministrator()) {
                organizationRepository.findByTagId("admin")
                        .ifPresent(org -> membershipService.add(user, org, OrganizationMembership.Source.SYSTEM));
            }
        }
    }
}

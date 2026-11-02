package org.example.github_api_practice.member;


import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "members",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_provider_provider_id",
                columnNames = {"provider", "provider_id"}
        )
)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private AuthProvider provider;

    @Column(name = "provider_id", nullable = false,
            length = 255, updatable = false)
    private String providerId;

    @Column(length = 320)
    private String email;

    @Column(name = "full_name", length = 255)
    private String fullName;

    public Member(AuthProvider provider, String providerId,
                  String email, String fullName) {
        this.provider = provider;
        this.providerId = providerId;
        updateProfile(email, fullName);
    }

    public void updateProfile(String email, String fullName) {
        this.email = email;
        this.fullName = fullName;
    }
}
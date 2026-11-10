package com.studyseat.config;

import com.studyseat.repository.MemberRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    UserDetailsService userDetailsService(MemberRepository members) {
        return loginId -> members.findByLoginId(loginId)
                .map(m -> User.withUsername(m.getLoginId()).password(m.getPassword()).roles(m.getRole()).build())
                .orElseThrow(() -> new UsernameNotFoundException("회원을 찾을 수 없습니다"));
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                        // 지점 둘러보기·도면 확인은 로그인 없이, 내 지점 등록과 예약은 로그인 후
                        .requestMatchers(HttpMethod.POST, "/branches/*/favorite").authenticated()
                        .requestMatchers("/", "/login", "/signup", "/reviews", "/branches/**", "/kiosk/**",
                                "/css/**", "/js/**", "/img/**", "/api/**", "/actuator/health").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", false).permitAll())
                .logout(l -> l.logoutSuccessUrl("/"));
        return http.build();
    }
}

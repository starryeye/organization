package dev.starryeye.organization.ldap.strategy;

/**
 * 한 검색에서 건너뛴 엔트리 수를 바깥에 알리는 자리(설계 2026-11-19 S5). {@code SkippedEntries#요약을_남긴다()} 가 경고 한 줄을 남긴 뒤
 * 건수가 있는 사유마다 한 번 부른다. connector-ldap 은 지표 라이브러리를 모르고, 이 인터페이스만 안다 — 지표로 옮기는 일은 앱 쪽 몫이다.
 */
public interface SkippedEntryObserver {

    /** 건너뛴 것이 어느 검색에서 나왔는가 */
    enum 검색 { 직원, 조직, member값 }

    /** 건너뛴 사유(설계 2026-11-19 S1) */
    enum 사유 { 식별_속성_없음, 한도_넘음, 아이디_겹침, 부모_조직_없음, 읽지_않은_member }

    /** @param count 이 검색에서 그 사유로 건너뛴 수(1 이상) */
    void skipped(검색 search, 사유 reason, int count);

    /** 아무것도 하지 않는 관찰자 */
    SkippedEntryObserver NOOP = (search, reason, count) -> { };
}

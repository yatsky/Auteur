package com.auteur.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DirectorNoteAddendumRepository extends JpaRepository<DirectorNoteAddendum, Long> {

    /** 拿一条 topic 的所有 addendum,按创建时间升序(早写的角色在前)。 */
    List<DirectorNoteAddendum> findByTopicIdOrderByCreatedAtAsc(Long topicId);

    /** topic 重新走流水线时清空旧 addendum,避免污染新一轮。 */
    void deleteByTopicId(Long topicId);
}

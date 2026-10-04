package org.booklore.service.virtualbook;

import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.VirtualBookDto;
import org.booklore.repository.VirtualBookRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VirtualBookService {

    private final VirtualBookRepository virtualBookRepository;

    @Transactional(readOnly = true)
    public Page<VirtualBookDto> list(String search, Pageable pageable) {
        if (search == null || search.isBlank()) {
            return virtualBookRepository.findAll(pageable).map(VirtualBookDto::from);
        }
        String keyword = search.trim();
        return virtualBookRepository
                .findByTitleContainingIgnoreCaseOrAuthorsContainingIgnoreCase(keyword, keyword, pageable)
                .map(VirtualBookDto::from);
    }
}

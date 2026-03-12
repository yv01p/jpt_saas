import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../api/client';
import type { Keyword } from '../api/types';

function removeKeywordById(keywords: Keyword[], id: string): Keyword[] {
  return keywords
    .filter((kw) => kw.id !== id)
    .map((kw) => ({ ...kw, children: removeKeywordById(kw.children, id) }));
}

type FormMode =
  | { kind: 'add'; parentId: string | null }
  | { kind: 'edit'; keyword: Keyword };

interface KeywordNodeProps {
  keyword: Keyword;
  onAddChild: (parentId: string) => void;
  onEdit: (keyword: Keyword) => void;
  onDelete: (keyword: Keyword) => void;
}

function KeywordNode({ keyword, onAddChild, onEdit, onDelete }: KeywordNodeProps) {
  return (
    <li>
      <span>{keyword.name}</span>
      <button
        type="button"
        aria-label="add child keyword"
        onClick={() => onAddChild(keyword.id)}
      >
        +
      </button>
      <button
        type="button"
        aria-label={`edit ${keyword.name}`}
        onClick={() => onEdit(keyword)}
      >
        Edit
      </button>
      <button
        type="button"
        aria-label={`delete ${keyword.name}`}
        onClick={() => onDelete(keyword)}
      >
        Delete
      </button>
      {keyword.children.length > 0 && (
        <ul>
          {keyword.children.map((child) => (
            <KeywordNode
              key={child.id}
              keyword={child}
              onAddChild={onAddChild}
              onEdit={onEdit}
              onDelete={onDelete}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

export default function KeywordsPage() {
  const queryClient = useQueryClient();
  const [formMode, setFormMode] = useState<FormMode | null>(null);
  const [inputName, setInputName] = useState('');

  const { data: keywords = [] } = useQuery<Keyword[]>({
    queryKey: ['keywords'],
    queryFn: () => apiFetch('/api/keywords'),
    staleTime: 10 * 60 * 1000,
  });

  const addKeyword = useMutation({
    mutationFn: ({ name, parentId }: { name: string; parentId: string | null }) =>
      apiFetch('/api/keywords', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, parentId }),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['keywords'] });
      setFormMode(null);
      setInputName('');
    },
  });

  const editKeyword = useMutation({
    mutationFn: ({ id, name, parentId }: { id: string; name: string; parentId: string | null }) =>
      apiFetch(`/api/keywords/${encodeURIComponent(id)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, parentId }),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['keywords'] });
      setFormMode(null);
      setInputName('');
    },
  });

  const deleteKeyword = useMutation({
    mutationFn: (id: string) =>
      apiFetch(`/api/keywords/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    onSuccess: (_data, id) => {
      queryClient.setQueryData<Keyword[]>(['keywords'], (old = []) =>
        removeKeywordById(old, id)
      );
    },
  });

  function handleAddChild(parentId: string) {
    setInputName('');
    setFormMode({ kind: 'add', parentId });
  }

  function handleEdit(keyword: Keyword) {
    setInputName(keyword.name);
    setFormMode({ kind: 'edit', keyword });
  }

  function handleDelete(keyword: Keyword) {
    deleteKeyword.mutate(keyword.id);
  }

  function handleSave() {
    if (!formMode) return;
    if (formMode.kind === 'add') {
      addKeyword.mutate({ name: inputName, parentId: formMode.parentId });
    } else {
      editKeyword.mutate({ id: formMode.keyword.id, name: inputName, parentId: formMode.keyword.parentId });
    }
  }

  return (
    <div className="keywords-page">
      <h1>Keywords</h1>
      <button
        type="button"
        onClick={() => { setInputName(''); setFormMode({ kind: 'add', parentId: null }); }}
      >
        Add Root Keyword
      </button>
      <ul>
        {keywords.map((kw) => (
          <KeywordNode
            key={kw.id}
            keyword={kw}
            onAddChild={handleAddChild}
            onEdit={handleEdit}
            onDelete={handleDelete}
          />
        ))}
      </ul>
      {formMode && (
        <div className="keyword-form">
          <label htmlFor="keyword-name-input">Keyword Name</label>
          <input
            id="keyword-name-input"
            type="text"
            value={inputName}
            onChange={(e) => setInputName(e.target.value)}
          />
          <button type="button" onClick={handleSave}>
            Save
          </button>
          <button type="button" onClick={() => setFormMode(null)}>
            Cancel
          </button>
        </div>
      )}
    </div>
  );
}

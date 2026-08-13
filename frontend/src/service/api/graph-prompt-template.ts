import { request } from '../request';

export function fetchGraphPromptTemplates() {
  return request<Api.GraphPromptTemplate.Item[]>({ url: '/graph-prompt-templates' });
}
export function createGraphPromptTemplate(data: Api.GraphPromptTemplate.Request) {
  return request<Api.GraphPromptTemplate.Item>({ url: '/graph-prompt-templates', method: 'post', data });
}
export function updateGraphPromptTemplate(id: number, data: Api.GraphPromptTemplate.Request) {
  return request<Api.GraphPromptTemplate.Item>({ url: `/graph-prompt-templates/${id}`, method: 'put', data });
}
export function deleteGraphPromptTemplate(id: number) {
  return request({ url: `/graph-prompt-templates/${id}`, method: 'delete' });
}

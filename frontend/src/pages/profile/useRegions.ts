import { useQuery } from '@tanstack/react-query'
import { regionsQuery } from '../../api/regions'
import type { RegionSource } from './regionSelection'

export function useRegions(enabled = true): RegionSource {
  const query = useQuery({ ...regionsQuery(), enabled })
  if (query.isPending) return { status: 'loading' }
  if (query.isError) return { status: 'error', onRetry: () => { void query.refetch() } }
  return { status: 'ready', options: query.data }
}
